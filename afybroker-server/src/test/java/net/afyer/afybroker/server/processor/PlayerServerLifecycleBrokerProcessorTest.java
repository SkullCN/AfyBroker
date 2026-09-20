package net.afyer.afybroker.server.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.Connection;
import com.alipay.remoting.rpc.RpcServer;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.BrokerClientInfoMessage;
import net.afyer.afybroker.core.message.PlayerServerConnectedMessage;
import net.afyer.afybroker.core.message.PlayerServerJoinMessage;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.event.PlayerServerConnectedEvent;
import net.afyer.afybroker.server.event.PlayerServerJoinEvent;
import net.afyer.afybroker.server.plugin.Event;
import net.afyer.afybroker.server.plugin.PluginManager;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerClientManager;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServerLifecycleBrokerProcessorTest {

    @Test
    void connectedKeepsPreviousServerUntilCurrentSessionJoinsAndRejectsStaleOrInvalidSources() {
        Fixture fixture = new Fixture();
        BrokerClientItem proxyA = fixture.client(BrokerClientType.PROXY, "proxy-main", "proxy-a:41000");
        BrokerClientItem proxyB = fixture.client(BrokerClientType.PROXY, "PROXY-MAIN", "proxy-b:42000");
        BrokerClientItem serverOld = fixture.client(BrokerClientType.SERVER, "server-old", "server-old:9000");
        BrokerClientItem serverNew = fixture.client(BrokerClientType.SERVER, "server-new", "server-new:9000");
        assertFalse(fixture.clients.isCurrent(proxyA));
        assertTrue(fixture.clients.isCurrent(proxyB));

        UUID uniqueId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        BrokerPlayer playerB = new BrokerPlayer(uniqueId, "player", sessionB, proxyB);
        playerB.setServer(serverOld);
        assertNull(fixture.players.addPlayer(playerB));

        PlayerServerConnectedBrokerProcessor connected = new PlayerServerConnectedBrokerProcessor();
        connected.setBrokerServer(fixture.server);
        PlayerServerJoinBrokerProcessor joined = new PlayerServerJoinBrokerProcessor();
        joined.setBrokerServer(fixture.server);

        connected.handleRequest(fixture.context(proxyA), mock(AsyncContext.class), new PlayerServerConnectedMessage()
                .setUniqueId(uniqueId).setSessionId(sessionA).setName("player").setServerName("server-new"));
        connected.handleRequest(fixture.context(serverNew), mock(AsyncContext.class), new PlayerServerConnectedMessage()
                .setUniqueId(uniqueId).setSessionId(sessionB).setName("player").setServerName("server-new"));
        connected.handleRequest(fixture.context(proxyB), mock(AsyncContext.class), new PlayerServerConnectedMessage()
                .setUniqueId(uniqueId).setName("player").setServerName("server-new"));
        assertSame(serverOld, playerB.getServer(), "connected must not switch the Broker's backend association");
        verify(fixture.pluginManager, never()).callEvent(any(PlayerServerConnectedEvent.class));

        connected.handleRequest(fixture.context(proxyB), mock(AsyncContext.class), new PlayerServerConnectedMessage()
                .setUniqueId(uniqueId).setSessionId(sessionB).setName("player").setServerName("server-new"));
        assertSame(serverOld, playerB.getServer());
        ArgumentCaptor<Event> connectedEvent = ArgumentCaptor.forClass(Event.class);
        verify(fixture.pluginManager).callEvent(connectedEvent.capture());
        assertTrue(connectedEvent.getValue() instanceof PlayerServerConnectedEvent);
        assertSame(playerB, ((PlayerServerConnectedEvent) connectedEvent.getValue()).getPlayer());

        joined.handleRequest(fixture.context(serverNew), mock(AsyncContext.class), new PlayerServerJoinMessage()
                .setUniqueId(uniqueId).setSessionId(sessionA).setName("player"));
        joined.handleRequest(fixture.context(serverNew), mock(AsyncContext.class), new PlayerServerJoinMessage()
                .setUniqueId(uniqueId).setName("player"));
        joined.handleRequest(fixture.context(proxyB), mock(AsyncContext.class), new PlayerServerJoinMessage()
                .setUniqueId(uniqueId).setSessionId(sessionB).setName("player"));
        assertSame(serverOld, playerB.getServer(), "stale session, missing session, and Proxy source cannot switch servers");
        verify(fixture.pluginManager, never()).callEvent(any(PlayerServerJoinEvent.class));

        joined.handleRequest(fixture.context(serverNew), mock(AsyncContext.class), new PlayerServerJoinMessage()
                .setUniqueId(uniqueId).setSessionId(sessionB).setName("player"));
        assertSame(serverNew, playerB.getServer());
        ArgumentCaptor<Event> joinEvent = ArgumentCaptor.forClass(Event.class);
        verify(fixture.pluginManager, times(2)).callEvent(joinEvent.capture());
        assertTrue(joinEvent.getAllValues().get(1) instanceof PlayerServerJoinEvent);
        PlayerServerJoinEvent event = (PlayerServerJoinEvent) joinEvent.getAllValues().get(1);
        assertSame(playerB, event.getPlayer());
        assertSame(serverOld, event.getPrevious());
        assertSame(serverNew, event.getCurrent());

        joined.handleRequest(fixture.context(serverNew), mock(AsyncContext.class), new PlayerServerJoinMessage()
                .setUniqueId(uniqueId).setSessionId(sessionA).setName("player"));
        connected.handleRequest(fixture.context(proxyB), mock(AsyncContext.class), new PlayerServerConnectedMessage()
                .setUniqueId(uniqueId).setSessionId(sessionA).setName("player").setServerName("server-new"));
        assertSame(serverNew, playerB.getServer());
        verify(fixture.pluginManager, times(1)).callEvent(any(PlayerServerJoinEvent.class));
        verify(fixture.pluginManager, times(1)).callEvent(any(PlayerServerConnectedEvent.class));
    }

    @Test
    void joinDoesNotBindClientReplacedAfterSourceLookup() throws Exception {
        Fixture fixture = new Fixture(true);
        BrokerClientItem proxy = fixture.client(BrokerClientType.PROXY, "proxy", "proxy:9000");
        BrokerClientItem backend = fixture.client(BrokerClientType.SERVER, "backend", "backend:9000");
        UUID uniqueId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        BrokerPlayer player = new BrokerPlayer(uniqueId, "player", sessionId, proxy);
        fixture.players.addPlayer(player);

        PlayerServerJoinBrokerProcessor joined = new PlayerServerJoinBrokerProcessor();
        joined.setBrokerServer(fixture.server);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                joined.handleRequest(fixture.context(backend), mock(AsyncContext.class), new PlayerServerJoinMessage()
                        .setUniqueId(uniqueId).setSessionId(sessionId).setName("player"));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        request.start();

        assertTrue(fixture.playerLookupStarted.await(5, TimeUnit.SECONDS));
        fixture.clients.beginConnection(backend.getAddress(), mock(Connection.class));
        fixture.allowPlayerLookup.countDown();
        request.join(5000);

        assertFalse(request.isAlive());
        assertNull(failure.get());
        assertNull(player.getServer());
        verify(fixture.pluginManager, never()).callEvent(any(PlayerServerJoinEvent.class));
    }

    private static final class Fixture {
        private final BrokerServer server = mock(BrokerServer.class);
        private final BrokerClientManager clients = new BrokerClientManager();
        private final BrokerPlayerManager players = new BrokerPlayerManager();
        private final PluginManager pluginManager = mock(PluginManager.class);
        private final CountDownLatch playerLookupStarted = new CountDownLatch(1);
        private final CountDownLatch allowPlayerLookup = new CountDownLatch(1);

        private Fixture() {
            this(false);
        }

        private Fixture(boolean blockPlayerLookup) {
            when(server.getClientManager()).thenReturn(clients);
            when(server.getPlayerManager()).thenReturn(players);
            when(server.getPluginManager()).thenReturn(pluginManager);
            when(server.getClient(any(BizContext.class))).thenAnswer(invocation -> {
                BizContext context = invocation.getArgument(0);
                return clients.getByConnection(context.getRemoteAddress(), context.getConnection());
            });
            when(server.getPlayer(any(UUID.class))).thenAnswer(invocation -> {
                if (blockPlayerLookup) {
                    playerLookupStarted.countDown();
                    try {
                        if (!allowPlayerLookup.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("player lookup was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return players.getPlayer(invocation.<UUID>getArgument(0));
            });
        }

        private BrokerClientItem client(String type, String name, String address) {
            Connection connection = mock(Connection.class);
            BrokerClientItem client = new BrokerClientItem(new BrokerClientInfoMessage()
                    .setName(name)
                    .setType(type)
                    .setAddress(address)
                    .setTags(Collections.emptySet())
                    .setMetadata(Collections.emptyMap())
                    .setServices(Collections.emptyList()), mock(RpcServer.class), Collections.emptyList(), connection);
            clients.beginConnection(address, connection);
            assertTrue(clients.registerReplacingLogicalClient(client).isRegistered());
            return client;
        }

        private BizContext context(BrokerClientItem client) {
            BizContext context = mock(BizContext.class);
            when(context.getRemoteAddress()).thenReturn(client.getAddress());
            when(context.getConnection()).thenReturn(client.getConnection());
            return context;
        }
    }
}
