package net.afyer.afybroker.velocity.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.afyer.afybroker.client.BrokerClient;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.session.PlayerSessionHandshake;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.afyer.afybroker.core.observability.Observability;
import net.afyer.afybroker.velocity.AfyBroker;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlayerListenerTest {

    @Test
    void disconnectBeforeDeferredLoginTaskStartsDoesNotRegisterOrCallBroker() throws Exception {
        Fixture fixture = new Fixture();
        EventTask task = fixture.listener.onConnect(fixture.connectEvent);
        assertTrue(task.requiresAsync());

        fixture.listener.onDisConnect(fixture.disconnectEvent);
        task.execute(mock(Continuation.class));

        verify(fixture.brokerClient, never()).invokeSync(any());
        verify(fixture.brokerClient, never()).oneway(any());
        assertNull(fixture.sessions.getConnection(fixture.player));
    }

    @Test
    void successfulLateLoginIsRemovedByItsSessionAfterOriginalPlayerDisconnects() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicReference<PlayerProxyConnectMessage> request = new AtomicReference<>();
        when(fixture.brokerClient.invokeSync(any())).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            requestStarted.countDown();
            allowResponse.await(5, TimeUnit.SECONDS);
            return new PlayerProxyConnectResult().setSuccess(true);
        });
        EventTask task = fixture.listener.onConnect(fixture.connectEvent);
        Thread loginTask = new Thread(() -> {
            try {
                task.execute(mock(Continuation.class));
            } finally {
                taskFinished.countDown();
            }
        });
        loginTask.start();

        assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
        fixture.listener.onDisConnect(fixture.disconnectEvent);
        allowResponse.countDown();
        assertTrue(taskFinished.await(5, TimeUnit.SECONDS));

        assertNotNull(request.get());
        ArgumentCaptor<PlayerProxyDisconnectMessage> disconnect =
                ArgumentCaptor.forClass(PlayerProxyDisconnectMessage.class);
        verify(fixture.brokerClient).oneway(disconnect.capture());
        assertEquals(fixture.player.getUniqueId(), disconnect.getValue().getUniqueId());
        assertEquals(request.get().getSessionId(), disconnect.getValue().getSessionId());
        assertNull(fixture.sessions.getCurrent(fixture.player.getUniqueId()));
    }

    @Test
    void lateSuccessfulLoginForAOnlyDisconnectsAAfterBIsAccepted() throws Exception {
        verifyLateLoginDoesNotAffectReplacement(true);
    }

    @Test
    void lateRejectedLoginForADoesNotDisconnectOrRemoveB() throws Exception {
        verifyLateLoginDoesNotAffectReplacement(false);
    }

    @Test
    void reconnectCreatesNewSessionAfterDisconnect() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.brokerClient.invokeSync(any())).thenReturn(new PlayerProxyConnectResult()
                .setSuccess(true).setServerName("lobby"));
        EventTask loginA = fixture.listener.onConnect(fixture.connectEvent);
        loginA.execute(mock(Continuation.class));
        PlayerSessionRegistry.Binding<Player> bindingA = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(bindingA);

        fixture.listener.onDisConnect(fixture.disconnectEvent);
        ArgumentCaptor<PlayerProxyDisconnectMessage> oldDisconnect =
                ArgumentCaptor.forClass(PlayerProxyDisconnectMessage.class);
        verify(fixture.brokerClient).oneway(oldDisconnect.capture());
        assertEquals(bindingA.getSessionId(), oldDisconnect.getValue().getSessionId());

        EventTask loginB = fixture.listener.onConnect(new PlayerChooseInitialServerEvent(fixture.playerB, null));
        loginB.execute(mock(Continuation.class));
        PlayerSessionRegistry.Binding<Player> bindingB = fixture.sessions.getCurrent(fixture.uniqueId);

        assertNotNull(bindingB);
        assertSame(fixture.playerB, bindingB.getPlayer());
        assertNotEquals(bindingA.getSessionId(), bindingB.getSessionId());
        assertTrue(fixture.sessions.isCurrent(bindingB));
    }

    @Test
    void handshakeRepliesOnlyToBackendRequestForCurrentSession() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.brokerClient.invokeSync(any())).thenReturn(new PlayerProxyConnectResult()
                .setSuccess(true).setServerName("lobby"));
        EventTask login = fixture.listener.onConnect(fixture.connectEvent);
        login.execute(mock(Continuation.class));
        PlayerSessionRegistry.Binding<Player> binding = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(binding);

        MinecraftChannelIdentifier channel = MinecraftChannelIdentifier.create("afybroker", "session");
        when(fixture.plugin.getSessionChannel()).thenReturn(channel);
        ServerConnection backend = mock(ServerConnection.class);
        when(backend.getPlayer()).thenReturn(fixture.player);
        PluginMessageEvent valid = new PluginMessageEvent(backend, mock(ChannelMessageSink.class), channel,
                PlayerSessionHandshake.request());
        fixture.listener.onPluginMessage(valid);
        assertFalse(valid.getResult().isAllowed());
        verify(backend).sendPluginMessage(channel, PlayerSessionHandshake.response(binding.getSessionId()));

        PluginMessageEvent clientOrigin = new PluginMessageEvent(fixture.player, mock(ChannelMessageSink.class), channel,
                PlayerSessionHandshake.request());
        fixture.listener.onPluginMessage(clientOrigin);
        assertFalse(clientOrigin.getResult().isAllowed());
        PluginMessageEvent malformed = new PluginMessageEvent(backend, mock(ChannelMessageSink.class), channel,
                new byte[]{1, 2, 3});
        fixture.listener.onPluginMessage(malformed);
        assertFalse(malformed.getResult().isAllowed());
        verify(backend, times(1)).sendPluginMessage(eq(channel), any(byte[].class));
        verify(fixture.player, never()).sendPluginMessage(eq(channel), any(byte[].class));
    }

    private static void verifyLateLoginDoesNotAffectReplacement(boolean oldLoginSucceeds) throws Exception {
        Fixture fixture = new Fixture();
        Player playerB = fixture.playerB;
        PlayerChooseInitialServerEvent eventA = new PlayerChooseInitialServerEvent(fixture.player, null);
        CountDownLatch requestAStarted = new CountDownLatch(1);
        CountDownLatch allowResponseA = new CountDownLatch(1);
        CountDownLatch taskAFinished = new CountDownLatch(1);
        AtomicReference<PlayerProxyConnectMessage> requestA = new AtomicReference<>();
        when(fixture.server.getServer("lobby")).thenReturn(java.util.Optional.of(fixture.lobby));
        when(fixture.brokerClient.invokeSync(any())).thenAnswer(invocation -> {
            PlayerProxyConnectMessage request = invocation.getArgument(0);
            if ("player-a".equals(request.getName())) {
                requestA.set(request);
                requestAStarted.countDown();
                if (!allowResponseA.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("old login response was not released");
                }
                return new PlayerProxyConnectResult().setSuccess(oldLoginSucceeds).setServerName("lobby");
            }
            return new PlayerProxyConnectResult().setSuccess(true).setServerName("lobby");
        });

        EventTask taskA = fixture.listener.onConnect(eventA);
        AtomicReference<Throwable> taskFailure = new AtomicReference<>();
        Thread loginA = new Thread(() -> {
            try {
                taskA.execute(mock(Continuation.class));
            } catch (Throwable e) {
                taskFailure.set(e);
            } finally {
                taskAFinished.countDown();
            }
        });
        loginA.start();
        assertTrue(requestAStarted.await(5, TimeUnit.SECONDS));

        PlayerChooseInitialServerEvent eventB = new PlayerChooseInitialServerEvent(playerB, null);
        EventTask taskB = fixture.listener.onConnect(eventB);
        taskB.execute(mock(Continuation.class));
        PlayerSessionRegistry.Binding<Player> bindingB = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(bindingB);
        assertSame(playerB, bindingB.getPlayer());
        assertSame(fixture.lobby, eventB.getInitialServer().orElse(null));

        allowResponseA.countDown();
        assertTrue(taskAFinished.await(5, TimeUnit.SECONDS));
        loginA.join(5000);
        assertFalse(loginA.isAlive());
        assertNull(taskFailure.get());
        assertSame(bindingB, fixture.sessions.getCurrent(fixture.uniqueId));
        assertTrue(fixture.sessions.isCurrent(bindingB));
        verify(playerB, never()).disconnect(any());
        verify(fixture.brokerClient, times(2)).invokeSync(any());
        if (oldLoginSucceeds) {
            ArgumentCaptor<PlayerProxyDisconnectMessage> disconnect =
                    ArgumentCaptor.forClass(PlayerProxyDisconnectMessage.class);
            verify(fixture.brokerClient).oneway(disconnect.capture());
            assertEquals(fixture.uniqueId, disconnect.getValue().getUniqueId());
            assertEquals(requestA.get().getSessionId(), disconnect.getValue().getSessionId());
        } else {
            verify(fixture.brokerClient, never()).oneway(any());
            verify(fixture.player, never()).disconnect(any());
        }
    }

    private static final class Fixture {
        private final UUID uniqueId = UUID.randomUUID();
        private final Player player = player("player-a");
        private final Player playerB = player("player-b");
        private final AfyBroker plugin = mock(AfyBroker.class);
        private final BrokerClient brokerClient = mock(BrokerClient.class);
        private final PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        private final ProxyServer server = mock(ProxyServer.class);
        private final RegisteredServer lobby = mock(RegisteredServer.class);
        private final PlayerChooseInitialServerEvent connectEvent;
        private final DisconnectEvent disconnectEvent;
        private final PlayerListener listener;

        private Fixture() {
            connectEvent = new PlayerChooseInitialServerEvent(player, null);
            disconnectEvent = new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
            when(plugin.getPlayerSessions()).thenReturn(sessions);
            when(plugin.getBrokerClient()).thenReturn(brokerClient);
            when(plugin.getServer()).thenReturn(server);
            when(server.getPlayerCount()).thenReturn(2);
            when(brokerClient.getObservability()).thenReturn(mock(Observability.class));
            when(server.getServer("lobby")).thenReturn(java.util.Optional.of(lobby));
            listener = new PlayerListener(plugin);
        }

        private Player player(String username) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(uniqueId);
            when(player.getUsername()).thenReturn(username);
            when(player.isActive()).thenReturn(true);
            return player;
        }
    }
}
