package net.afyer.afybroker.server.task;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.Connection;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.rpc.RpcServer;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.BrokerClientInfoMessage;
import net.afyer.afybroker.core.message.PlayerHeartbeatValidateMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.observability.Observability;
import net.afyer.afybroker.core.observability.PlayerEventType;
import net.afyer.afybroker.core.observability.PlayerObservation;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.event.PlayerProxyLoginEvent;
import net.afyer.afybroker.server.event.PlayerProxyLogoutEvent;
import net.afyer.afybroker.server.plugin.Event;
import net.afyer.afybroker.server.plugin.PluginManager;
import net.afyer.afybroker.server.processor.PlayerProxyConnectBrokerProcessor;
import net.afyer.afybroker.server.processor.PlayerProxyDisconnectBrokerProcessor;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerClientManager;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerSessionLifecycleBrokerProcessorTest {

    @Test
    void heartbeatResultFromReplacedRpcConnectionCannotLogoutBeforeSnapshotRestoresSameSession() throws Exception {
        Fixture fixture = new Fixture();
        String address = "proxy:9000";
        TestProxyClient oldProxy = fixture.proxy(address);
        UUID uniqueId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PlayerProxyConnectBrokerProcessor connect = new PlayerProxyConnectBrokerProcessor();
        connect.setBrokerServer(fixture.server);
        assertTrue(connect(connect, fixture.context(oldProxy), uniqueId, sessionId).isSuccess());
        BrokerPlayer original = fixture.players.getPlayer(uniqueId);

        PlayerHeartbeatValidateTask heartbeat = new PlayerHeartbeatValidateTask(fixture.server);
        heartbeat.validateOnce();
        InvokeCallback delayed = oldProxy.lastCallback;

        Connection replacementConnection = mock(Connection.class);
        fixture.clients.beginConnection(address, replacementConnection);
        delayed.onResponse(Collections.singletonList(session(uniqueId, sessionId)));
        assertSame(original, fixture.players.getPlayer(uniqueId));
        assertSame(original, fixture.players.getPlayer("player"));

        TestProxyClient replacementProxy = fixture.proxy(address, replacementConnection);
        BrokerPlayer restored = new BrokerPlayer(uniqueId, "player", sessionId, replacementProxy);
        PlayerProxyConnectBrokerProcessor.SnapshotRegistration registration =
                PlayerProxyConnectBrokerProcessor.registerSnapshotPlayer(fixture.server, restored);
        assertFalse(registration.isNewLogin());
        BrokerPlayer rebound = fixture.players.getPlayer(uniqueId);
        assertEquals(sessionId, rebound.getSessionId());
        assertSame(replacementProxy, rebound.getProxy());
        assertNotSame(original, rebound);
        assertSame(rebound, fixture.players.getPlayer("player"));

        // Even a duplicate old response after the replacement snapshot is pinned to A's exact client and player.
        delayed.onResponse(Collections.singletonList(session(uniqueId, sessionId)));
        assertSame(rebound, fixture.players.getPlayer(uniqueId));
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(fixture.pluginManager, times(1)).callEvent(events.capture());
        assertTrue(events.getAllValues().get(0) instanceof PlayerProxyLoginEvent);

        heartbeat.validateOnce();
        replacementProxy.lastCallback.onResponse(Collections.singletonList(session(uniqueId, sessionId)));
        assertNull(fixture.players.getPlayer(uniqueId));
        events = ArgumentCaptor.forClass(Event.class);
        verify(fixture.pluginManager, times(2)).callEvent(events.capture());
        assertTrue(events.getAllValues().get(1) instanceof PlayerProxyLogoutEvent);
        verify(fixture.observability, times(2)).onPlayer(any(PlayerObservation.class));
    }

    @Test
    void delayedHeartbeatAndDisconnectForA_CannotRemoveReplacementSessionB() throws Exception {
        Fixture fixture = new Fixture();
        TestProxyClient proxy = fixture.proxy("proxy:9000");
        UUID uniqueId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        PlayerProxyConnectBrokerProcessor connect = new PlayerProxyConnectBrokerProcessor();
        connect.setBrokerServer(fixture.server);
        PlayerProxyDisconnectBrokerProcessor disconnect = new PlayerProxyDisconnectBrokerProcessor();
        disconnect.setBrokerServer(fixture.server);

        assertTrue(connect(connect, fixture.context(proxy), uniqueId, sessionA).isSuccess());
        BrokerPlayer playerA = fixture.players.getPlayer(uniqueId);
        assertSame(playerA, fixture.players.getPlayer("player"));

        PlayerHeartbeatValidateTask heartbeat = new PlayerHeartbeatValidateTask(fixture.server);
        heartbeat.validateOnce();
        PlayerHeartbeatValidateMessage queryA = (PlayerHeartbeatValidateMessage) proxy.lastRequest;
        InvokeCallback delayedA = proxy.lastCallback;
        assertEquals(sessionA, queryA.getPlayers().get(0).getSessionId());

        disconnect.handleRequest(fixture.context(proxy), mock(AsyncContext.class), disconnect(uniqueId, sessionA));
        assertNull(fixture.players.getPlayer(uniqueId));
        assertTrue(connect(connect, fixture.context(proxy), uniqueId, sessionB).isSuccess());
        BrokerPlayer playerB = fixture.players.getPlayer(uniqueId);
        assertSame(playerB, fixture.players.getPlayer("player"));

        // A stale packet from the still-current proxy is rejected by session ID.
        disconnect.handleRequest(fixture.context(proxy), mock(AsyncContext.class), disconnect(uniqueId, sessionA));
        // A callback captured before A disconnected is also rejected after B logs in.
        delayedA.onResponse(Collections.singletonList(session(uniqueId, sessionA)));
        assertSame(playerB, fixture.players.getPlayer(uniqueId));
        assertSame(playerB, fixture.players.getPlayer("player"));

        heartbeat.validateOnce();
        PlayerHeartbeatValidateMessage queryB = (PlayerHeartbeatValidateMessage) proxy.lastRequest;
        InvokeCallback delayedB = proxy.lastCallback;
        assertEquals(sessionB, queryB.getPlayers().get(0).getSessionId());
        delayedB.onResponse(Collections.singletonList(session(uniqueId, sessionB)));

        assertNull(fixture.players.getPlayer(uniqueId));
        assertNull(fixture.players.getPlayer("player"));
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(fixture.pluginManager, times(4)).callEvent(events.capture());
        assertTrue(events.getAllValues().get(0) instanceof PlayerProxyLoginEvent);
        assertTrue(events.getAllValues().get(1) instanceof PlayerProxyLogoutEvent);
        assertTrue(events.getAllValues().get(2) instanceof PlayerProxyLoginEvent);
        assertTrue(events.getAllValues().get(3) instanceof PlayerProxyLogoutEvent);
        assertEquals(Arrays.asList(playerA, playerB), Arrays.asList(
                ((PlayerProxyLogoutEvent) events.getAllValues().get(1)).getPlayer(),
                ((PlayerProxyLogoutEvent) events.getAllValues().get(3)).getPlayer()));

        ArgumentCaptor<PlayerObservation> observations = ArgumentCaptor.forClass(PlayerObservation.class);
        verify(fixture.observability, times(4)).onPlayer(observations.capture());
        assertEquals(Arrays.asList(PlayerEventType.JOIN, PlayerEventType.LEAVE,
                        PlayerEventType.JOIN, PlayerEventType.LEAVE),
                Arrays.asList(observations.getAllValues().get(0).getEventType(),
                        observations.getAllValues().get(1).getEventType(),
                        observations.getAllValues().get(2).getEventType(),
                        observations.getAllValues().get(3).getEventType()));
        assertEquals(Arrays.asList(1, 0, 1, 0), Arrays.asList(
                observations.getAllValues().get(0).getOnlinePlayers(),
                observations.getAllValues().get(1).getOnlinePlayers(),
                observations.getAllValues().get(2).getOnlinePlayers(),
                observations.getAllValues().get(3).getOnlinePlayers()));
    }

    private static PlayerProxyConnectResult connect(PlayerProxyConnectBrokerProcessor processor, BizContext context,
                                                      UUID uniqueId, UUID sessionId) {
        return (PlayerProxyConnectResult) processor.handleRequest(context, new PlayerProxyConnectMessage()
                .setUniqueId(uniqueId).setSessionId(sessionId).setName("player"));
    }

    private static PlayerProxyDisconnectMessage disconnect(UUID uniqueId, UUID sessionId) {
        return new PlayerProxyDisconnectMessage().setUniqueId(uniqueId).setSessionId(sessionId).setName("player");
    }

    private static PlayerSessionInfo session(UUID uniqueId, UUID sessionId) {
        return new PlayerSessionInfo().setUniqueId(uniqueId).setSessionId(sessionId).setName("player");
    }

    private static final class Fixture {
        private final BrokerClientManager clients = new BrokerClientManager();
        private final BrokerPlayerManager players = new BrokerPlayerManager();
        private final PluginManager pluginManager = mock(PluginManager.class);
        private final Observability observability = mock(Observability.class);
        private final BrokerServer server = mock(BrokerServer.class);

        private Fixture() {
            when(server.getClientManager()).thenReturn(clients);
            when(server.getPlayerManager()).thenReturn(players);
            when(server.getPluginManager()).thenReturn(pluginManager);
            when(server.getObservability()).thenReturn(observability);
            when(server.getPlayer(any(UUID.class))).thenAnswer(invocation ->
                    players.getPlayer(invocation.<UUID>getArgument(0)));
            when(server.getClient(any(BizContext.class))).thenAnswer(invocation -> {
                BizContext context = invocation.getArgument(0);
                return clients.getByConnection(context.getRemoteAddress(), context.getConnection());
            });
        }

        private TestProxyClient proxy(String address) {
            Connection connection = mock(Connection.class);
            return proxy(address, connection);
        }

        private TestProxyClient proxy(String address, Connection connection) {
            TestProxyClient client = new TestProxyClient(address, connection);
            clients.beginConnection(address, connection);
            if (!clients.register(client)) {
                throw new AssertionError("proxy client registration failed");
            }
            return client;
        }

        private BizContext context(BrokerClientItem client) {
            BizContext context = mock(BizContext.class);
            when(context.getRemoteAddress()).thenReturn(client.getAddress());
            when(context.getConnection()).thenReturn(client.getConnection());
            return context;
        }
    }

    private static final class TestProxyClient extends BrokerClientItem {
        private Object lastRequest;
        private InvokeCallback lastCallback;

        private TestProxyClient(String address, Connection connection) {
            super(new BrokerClientInfoMessage()
                    .setName(address)
                    .setType(BrokerClientType.PROXY)
                    .setAddress(address), mock(RpcServer.class), Collections.emptyList(), connection);
        }

        @Override
        public void invokeWithCallback(Object request, InvokeCallback invokeCallback)
                throws RemotingException, InterruptedException {
            lastRequest = request;
            lastCallback = invokeCallback;
        }
    }
}
