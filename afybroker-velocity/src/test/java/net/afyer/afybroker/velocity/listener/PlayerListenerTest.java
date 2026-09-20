package net.afyer.afybroker.velocity.listener;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import net.afyer.afybroker.client.BrokerClient;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
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

    private static final class Fixture {
        private final UUID uniqueId = UUID.randomUUID();
        private final Player player = mock(Player.class);
        private final AfyBroker plugin = mock(AfyBroker.class);
        private final BrokerClient brokerClient = mock(BrokerClient.class);
        private final PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        private final PlayerChooseInitialServerEvent connectEvent;
        private final DisconnectEvent disconnectEvent;
        private final PlayerListener listener;

        private Fixture() {
            when(player.getUniqueId()).thenReturn(uniqueId);
            when(player.getUsername()).thenReturn("player");
            when(player.isActive()).thenReturn(true);
            connectEvent = new PlayerChooseInitialServerEvent(player, null);
            disconnectEvent = new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
            when(plugin.getPlayerSessions()).thenReturn(sessions);
            when(plugin.getBrokerClient()).thenReturn(brokerClient);
            listener = new PlayerListener(plugin);
        }
    }
}
