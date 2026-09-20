package net.afyer.afybroker.bungee.listener;

import net.afyer.afybroker.bungee.AfyBroker;
import net.afyer.afybroker.client.BrokerClient;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.message.PlayerServerConnectedMessage;
import net.afyer.afybroker.core.session.PlayerSessionHandshake;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.scheduler.TaskScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerListenerTest {

    private static final ProxyServer PROXY = mock(ProxyServer.class);

    @org.junit.jupiter.api.BeforeAll
    static void installProxy() {
        ProxyServer.setInstance(PROXY);
    }

    @Test
    void acceptedLoginAnswersBackendHandshakeAndKeepsSessionAcrossServerSwitch() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.proxy.getPlayer(fixture.uniqueId)).thenReturn(fixture.player);
        when(fixture.proxy.getServerInfo("lobby")).thenReturn(fixture.lobby);
        when(fixture.brokerClient.invokeSync(any())).thenReturn(new PlayerProxyConnectResult()
                .setSuccess(true).setServerName("lobby"));

        ServerConnectEvent login = new ServerConnectEvent(fixture.player, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY);
        fixture.listener.onConnect(login);

        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(binding);
        assertSame(fixture.player, binding.getPlayer());
        assertSame(fixture.lobby, login.getTarget());
        assertTrue(fixture.sessions.isCurrent(binding));

        when(fixture.player.getServer()).thenReturn(fixture.backend);
        PluginMessageEvent request = new PluginMessageEvent(fixture.backend, fixture.player,
                PlayerSessionHandshake.CHANNEL, PlayerSessionHandshake.request());
        fixture.listener.onPluginMessage(request);
        assertTrue(request.isCancelled());
        verify(fixture.backend).sendData(PlayerSessionHandshake.CHANNEL,
                PlayerSessionHandshake.response(binding.getSessionId()));

        ServerConnectEvent switchEvent = new ServerConnectEvent(fixture.player, fixture.initial,
                ServerConnectEvent.Reason.COMMAND);
        fixture.listener.onConnect(switchEvent);
        verify(fixture.brokerClient, times(1)).invokeSync(any());

        Server newBackend = mock(Server.class);
        ServerInfo newBackendInfo = mock(ServerInfo.class);
        when(newBackend.getInfo()).thenReturn(newBackendInfo);
        when(newBackendInfo.getName()).thenReturn("arena");
        fixture.listener.onServerConnected(new ServerConnectedEvent(fixture.player, newBackend));
        assertEquals(1, fixture.asyncTasks.size());
        fixture.runAsync();
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(fixture.brokerClient).oneway(sent.capture());
        assertInstanceOf(PlayerServerConnectedMessage.class, sent.getValue());
        assertEquals(binding.getSessionId(), ((PlayerServerConnectedMessage) sent.getValue()).getSessionId());
    }

    @Test
    void clientOriginAndMalformedHandshakeMessagesAreCancelledWithoutAReply() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.proxy.getPlayer(fixture.uniqueId)).thenReturn(fixture.player);
        when(fixture.proxy.getServerInfo("lobby")).thenReturn(fixture.lobby);
        when(fixture.brokerClient.invokeSync(any())).thenReturn(new PlayerProxyConnectResult()
                .setSuccess(true).setServerName("lobby"));
        fixture.listener.onConnect(new ServerConnectEvent(fixture.player, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY));

        PluginMessageEvent clientOrigin = new PluginMessageEvent(fixture.player, fixture.backend,
                PlayerSessionHandshake.CHANNEL, PlayerSessionHandshake.request());
        fixture.listener.onPluginMessage(clientOrigin);
        assertTrue(clientOrigin.isCancelled());

        PluginMessageEvent malformed = new PluginMessageEvent(fixture.backend, fixture.player,
                PlayerSessionHandshake.CHANNEL, new byte[]{1, 2, 3});
        fixture.listener.onPluginMessage(malformed);
        assertTrue(malformed.isCancelled());
        verify(fixture.backend, never()).sendData(eq(PlayerSessionHandshake.CHANNEL), any(byte[].class));
    }

    @Test
    void rejectedDuplicateLoginLeavesAcceptedPlayerCurrent() throws Exception {
        Fixture fixture = new Fixture();
        ProxiedPlayer playerB = fixture.player("player-b");
        when(fixture.proxy.getPlayer(fixture.uniqueId)).thenReturn(fixture.player, playerB);
        when(fixture.proxy.getServerInfo("lobby")).thenReturn(fixture.lobby);
        when(fixture.brokerClient.invokeSync(any()))
                .thenReturn(new PlayerProxyConnectResult().setSuccess(true).setServerName("lobby"))
                .thenReturn(new PlayerProxyConnectResult().setSuccess(false));

        fixture.listener.onConnect(new ServerConnectEvent(fixture.player, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY));
        PlayerSessionRegistry.Binding<ProxiedPlayer> acceptedA = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(acceptedA);

        ServerConnectEvent duplicateLogin = new ServerConnectEvent(playerB, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY);
        fixture.listener.onConnect(duplicateLogin);

        assertSame(acceptedA, fixture.sessions.getCurrent(fixture.uniqueId));
        assertTrue(fixture.sessions.isCurrent(acceptedA));
        assertTrue(duplicateLogin.isCancelled());
        verify(playerB).disconnect(any(net.md_5.bungee.api.chat.BaseComponent.class));
    }

    @Test
    void reconnectCreatesNewSessionAfterDisconnect() throws Exception {
        Fixture fixture = new Fixture();
        ProxiedPlayer playerB = fixture.player("player-b");
        when(fixture.proxy.getPlayer(fixture.uniqueId)).thenReturn(fixture.player, playerB);
        when(fixture.proxy.getServerInfo("lobby")).thenReturn(fixture.lobby);
        when(fixture.brokerClient.invokeSync(any())).thenReturn(new PlayerProxyConnectResult()
                .setSuccess(true).setServerName("lobby"));

        fixture.listener.onConnect(new ServerConnectEvent(fixture.player, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY));
        PlayerSessionRegistry.Binding<ProxiedPlayer> bindingA = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(bindingA);
        fixture.listener.onDisConnect(new PlayerDisconnectEvent(fixture.player));
        assertEquals(1, fixture.asyncTasks.size());
        fixture.runAsync();
        ArgumentCaptor<Object> disconnect = ArgumentCaptor.forClass(Object.class);
        verify(fixture.brokerClient).oneway(disconnect.capture());
        assertTrue(disconnect.getValue() instanceof PlayerProxyDisconnectMessage);
        assertEquals(bindingA.getSessionId(), ((PlayerProxyDisconnectMessage) disconnect.getValue()).getSessionId());

        fixture.listener.onConnect(new ServerConnectEvent(playerB, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY));
        PlayerSessionRegistry.Binding<ProxiedPlayer> bindingB = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(bindingB);
        assertSame(playerB, bindingB.getPlayer());
        assertNotEquals(bindingA.getSessionId(), bindingB.getSessionId());
        assertTrue(fixture.sessions.isCurrent(bindingB));
    }

    @Test
    void lateSuccessfulLoginForAOnlyDisconnectsAAfterBIsAccepted() throws Exception {
        verifyLateLoginDoesNotAffectReplacement(true);
    }

    @Test
    void lateRejectedLoginForADoesNotDisconnectOrRemoveB() throws Exception {
        verifyLateLoginDoesNotAffectReplacement(false);
    }

    private static void verifyLateLoginDoesNotAffectReplacement(boolean oldLoginSucceeds) throws Exception {
        Fixture fixture = new Fixture();
        ProxiedPlayer playerB = fixture.player("player-b");
        when(fixture.proxy.getPlayer(fixture.uniqueId)).thenReturn(playerB);
        when(fixture.proxy.getServerInfo("lobby")).thenReturn(fixture.lobby);
        CountDownLatch requestAStarted = new CountDownLatch(1);
        CountDownLatch allowResponseA = new CountDownLatch(1);
        CountDownLatch eventAFinished = new CountDownLatch(1);
        AtomicReference<PlayerProxyConnectMessage> requestA = new AtomicReference<>();
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

        ServerConnectEvent eventA = new ServerConnectEvent(fixture.player, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY);
        AtomicReference<Throwable> eventFailure = new AtomicReference<>();
        Thread loginA = new Thread(() -> {
            try {
                fixture.listener.onConnect(eventA);
            } catch (Throwable e) {
                eventFailure.set(e);
            } finally {
                eventAFinished.countDown();
            }
        });
        loginA.start();
        assertTrue(requestAStarted.await(5, TimeUnit.SECONDS));

        ServerConnectEvent eventB = new ServerConnectEvent(playerB, fixture.initial,
                ServerConnectEvent.Reason.JOIN_PROXY);
        fixture.listener.onConnect(eventB);
        PlayerSessionRegistry.Binding<ProxiedPlayer> bindingB = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(bindingB);
        assertSame(playerB, bindingB.getPlayer());
        assertSame(fixture.lobby, eventB.getTarget());

        allowResponseA.countDown();
        assertTrue(eventAFinished.await(5, TimeUnit.SECONDS));
        loginA.join(5000);
        assertFalse(loginA.isAlive());
        assertNull(eventFailure.get());
        assertSame(bindingB, fixture.sessions.getCurrent(fixture.uniqueId));
        assertTrue(fixture.sessions.isCurrent(bindingB));
        verify(playerB, never()).disconnect(any(net.md_5.bungee.api.chat.BaseComponent.class));
        verify(fixture.brokerClient, times(2)).invokeSync(any());
        if (oldLoginSucceeds) {
            ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            assertEquals(1, fixture.asyncTasks.size());
            fixture.runAsync();
            verify(fixture.brokerClient).oneway(sent.capture());
            assertTrue(sent.getValue() instanceof PlayerProxyDisconnectMessage);
            assertEquals(fixture.uniqueId, ((PlayerProxyDisconnectMessage) sent.getValue()).getUniqueId());
            assertEquals(requestA.get().getSessionId(),
                    ((PlayerProxyDisconnectMessage) sent.getValue()).getSessionId());
        } else {
            assertTrue(fixture.asyncTasks.isEmpty());
            verify(fixture.brokerClient, never()).oneway(any());
            verify(fixture.player, never()).disconnect(any(net.md_5.bungee.api.chat.BaseComponent.class));
        }
    }

    private static final class Fixture {
        private final UUID uniqueId = UUID.randomUUID();
        private final ProxiedPlayer player = player("player-a");
        private final ProxyServer proxy = PROXY;
        private final TaskScheduler scheduler = mock(TaskScheduler.class);
        private final ServerInfo initial = mock(ServerInfo.class);
        private final ServerInfo lobby = mock(ServerInfo.class);
        private final Server backend = mock(Server.class);
        private final AfyBroker plugin = mock(AfyBroker.class);
        private final BrokerClient brokerClient = mock(BrokerClient.class);
        private final PlayerSessionRegistry<ProxiedPlayer> sessions = new PlayerSessionRegistry<>();
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final PlayerListener listener;

        private Fixture() {
            reset(proxy);
            when(proxy.getScheduler()).thenReturn(scheduler);
            when(proxy.getOnlineCount()).thenReturn(1);
            when(plugin.getBrokerClient()).thenReturn(brokerClient);
            when(plugin.getPlayerSessions()).thenReturn(sessions);
            when(brokerClient.getObservability()).thenReturn(mock(net.afyer.afybroker.core.observability.Observability.class));
            when(backend.getInfo()).thenReturn(initial);
            when(initial.getName()).thenReturn("initial");
            when(player.getServer()).thenReturn(backend);
            when(scheduler.runAsync(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                asyncTasks.add(invocation.getArgument(1));
                return mock(net.md_5.bungee.api.scheduler.ScheduledTask.class);
            });
            listener = new PlayerListener(plugin);
        }

        private ProxiedPlayer player(String name) {
            ProxiedPlayer player = mock(ProxiedPlayer.class);
            when(player.getUniqueId()).thenReturn(uniqueId);
            when(player.getName()).thenReturn(name);
            return player;
        }

        private void runAsync() {
            assertFalse(asyncTasks.isEmpty());
            asyncTasks.remove(0).run();
        }
    }
}
