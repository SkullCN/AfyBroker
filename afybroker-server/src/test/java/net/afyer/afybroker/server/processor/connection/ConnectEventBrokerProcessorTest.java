package net.afyer.afybroker.server.processor.connection;

import com.alipay.remoting.Connection;
import com.alipay.remoting.InvokeCallback;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.observability.Observability;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.event.PlayerProxyLoginEvent;
import net.afyer.afybroker.server.plugin.PluginManager;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerClientManager;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectEventBrokerProcessorTest {

    @Test
    void backendThenProxySnapshotsPairOnlyMatchingSessionAndIgnoreStaleBackendSnapshot() {
        Fixture fixture = new Fixture();
        BrokerClientItem backendB = client(fixture.clientManager, BrokerClientType.SERVER, "backend-b");
        BrokerClientItem backendA = client(fixture.clientManager, BrokerClientType.SERVER, "backend-a");
        BrokerClientItem proxyB = client(fixture.clientManager, BrokerClientType.PROXY, "proxy-b");
        UUID uniqueId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        ConnectEventBrokerProcessor processor = fixture.processor();

        invoke(processor.registerPlayerBukkitCallback(backendB), player(uniqueId, sessionB));
        invoke(processor.registerPlayerBukkitCallback(backendA), player(uniqueId, sessionA));
        invoke(processor.registerPlayerBungeeCallback(proxyB), player(uniqueId, sessionB));

        BrokerPlayer current = fixture.players.getPlayer(uniqueId);
        assertSame(backendB, current.getServer());
        assertEquals(1, fixture.players.size());

        invoke(processor.registerPlayerBukkitCallback(backendA), player(uniqueId, sessionA));
        assertSame(backendB, current.getServer());
    }

    @Test
    void proxyCallbackCannotRegisterPlayerAfterReplacementBegins() {
        Fixture fixture = new Fixture();
        BrokerClientItem oldProxy = client(fixture.clientManager, BrokerClientType.PROXY, "proxy");
        Connection newConnection = mock(Connection.class);
        UUID uniqueId = UUID.randomUUID();
        ConnectEventBrokerProcessor processor = fixture.processor();
        List<PlayerSessionInfo> response = replaceConnectionBeforeRows(fixture.clientManager,
                oldProxy.getAddress(), newConnection, player(uniqueId, UUID.randomUUID()));

        processor.registerPlayerBungeeCallback(oldProxy).onResponse(response);

        assertNull(fixture.players.getPlayer(uniqueId));
        assertFalse(fixture.clientManager.isCurrent(oldProxy));
        verify(fixture.pluginManager, never()).callEvent(any(PlayerProxyLoginEvent.class));
    }

    @Test
    void backendCallbackCannotLeavePendingPairAfterItsConnectionIsReplaced() {
        Fixture fixture = new Fixture();
        BrokerClientItem oldBackend = client(fixture.clientManager, BrokerClientType.SERVER, "backend");
        Connection newConnection = mock(Connection.class);
        UUID uniqueId = UUID.randomUUID();
        ConnectEventBrokerProcessor processor = fixture.processor();
        List<PlayerSessionInfo> response = replaceConnectionBeforeRows(fixture.clientManager,
                oldBackend.getAddress(), newConnection, player(uniqueId, UUID.randomUUID()));

        processor.registerPlayerBukkitCallback(oldBackend).onResponse(response);

        assertTrue(processor.playerBukkitMap.isEmpty());
        assertFalse(fixture.clientManager.isCurrent(oldBackend));
    }

    @Test
    void replacementWaitsUntilSnapshotMutationFinishesItsClientCriticalSection() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        BlockingPlayerManager players = new BlockingPlayerManager(uniqueId);
        Fixture fixture = new Fixture(players);
        BrokerClientItem oldProxy = client(fixture.clientManager, BrokerClientType.PROXY, "proxy");
        Connection newConnection = mock(Connection.class);
        ConnectEventBrokerProcessor processor = fixture.processor();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        Thread callback = new Thread(() -> {
            try {
                invoke(processor.registerPlayerBungeeCallback(oldProxy), player(uniqueId, UUID.randomUUID()));
            } catch (Throwable e) {
                callbackFailure.set(e);
            }
        });
        callback.start();

        assertTrue(players.addStarted.await(5, TimeUnit.SECONDS));
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            replacementStarted.countDown();
            fixture.clientManager.beginConnection(oldProxy.getAddress(), newConnection);
            replacementFinished.countDown();
        });
        replacement.start();
        assertTrue(replacementStarted.await(5, TimeUnit.SECONDS));
        assertFalse(replacementFinished.await(100, TimeUnit.MILLISECONDS));

        players.allowAdd.countDown();
        callback.join(5000);
        replacement.join(5000);
        assertFalse(callback.isAlive());
        assertFalse(replacement.isAlive());
        assertNull(callbackFailure.get());
        assertFalse(fixture.clientManager.isCurrent(oldProxy));
        assertSame(oldProxy, players.getPlayer(uniqueId).getProxy());
    }

    @Test
    void staleRpcConnectionCannotReplaceOrRemoveCurrentClient() {
        BrokerClientManager manager = new BrokerClientManager();
        Connection oldConnection = mock(Connection.class);
        Connection currentConnection = mock(Connection.class);
        BrokerClientItem oldClient = client("PROXY", "proxy:9000", oldConnection);
        BrokerClientItem currentClient = client("PROXY", "proxy:9000", currentConnection);

        manager.beginConnection("proxy:9000", oldConnection);
        manager.beginConnection("proxy:9000", currentConnection);
        assertFalse(manager.register(oldClient));
        assertNull(manager.remove("proxy:9000", oldConnection));
        assertTrue(manager.register(currentClient));
        assertNull(manager.remove("proxy:9000", oldConnection));
        assertSame(currentClient, manager.getByAddress("proxy:9000"));
    }

    @Test
    void sameSessionProxyReconnectPreservesBoundBackend() {
        BrokerPlayerManager manager = new BrokerPlayerManager();
        BrokerClientItem oldProxy = mock(BrokerClientItem.class);
        BrokerClientItem newProxy = mock(BrokerClientItem.class);
        BrokerClientItem backend = mock(BrokerClientItem.class);
        UUID uniqueId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        BrokerPlayer previous = new BrokerPlayer(uniqueId, "player", sessionId, oldProxy);
        previous.setServer(backend);
        BrokerPlayer replacement = new BrokerPlayer(uniqueId, "player", sessionId, newProxy);
        manager.addPlayer(previous);

        assertTrue(manager.replaceSession(previous, replacement));
        assertSame(replacement, manager.getPlayer(uniqueId));
        assertSame(backend, replacement.getServer());
    }

    private static List<PlayerSessionInfo> replaceConnectionBeforeRows(BrokerClientManager manager,
                                                                        String address,
                                                                        Connection replacement,
                                                                        PlayerSessionInfo player) {
        return new ArrayList<PlayerSessionInfo>(Collections.singletonList(player)) {
            @Override
            public void forEach(Consumer<? super PlayerSessionInfo> action) {
                Thread begin = new Thread(() -> manager.beginConnection(address, replacement));
                begin.start();
                try {
                    begin.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                if (begin.isAlive()) {
                    throw new AssertionError("replacement connection did not start");
                }
                super.forEach(action);
            }
        };
    }

    private static void invoke(InvokeCallback callback, PlayerSessionInfo player) {
        callback.onResponse(Collections.singletonList(player));
    }

    private static PlayerSessionInfo player(UUID uniqueId, UUID sessionId) {
        return new PlayerSessionInfo()
                .setUniqueId(uniqueId)
                .setSessionId(sessionId)
                .setName("player");
    }

    private static BrokerClientItem client(BrokerClientManager manager, String type, String address) {
        Connection connection = mock(Connection.class);
        BrokerClientItem client = client(type, address, connection);
        manager.beginConnection(address, connection);
        assertTrue(manager.register(client));
        return client;
    }

    private static BrokerClientItem client(String type, String address, Connection connection) {
        BrokerClientItem client = mock(BrokerClientItem.class);
        when(client.getType()).thenReturn(type);
        when(client.getAddress()).thenReturn(address);
        when(client.getConnection()).thenReturn(connection);
        when(client.getName()).thenReturn(address);
        return client;
    }

    private static final class Fixture {
        private final BrokerServer server = mock(BrokerServer.class);
        private final BrokerClientManager clientManager = new BrokerClientManager();
        private final BrokerPlayerManager players;
        private final PluginManager pluginManager = mock(PluginManager.class);

        private Fixture() {
            this(new BrokerPlayerManager());
        }

        private Fixture(BrokerPlayerManager players) {
            this.players = players;
            when(server.getClientManager()).thenReturn(clientManager);
            when(server.getPlayerManager()).thenReturn(players);
            when(server.getObservability()).thenReturn(Observability.NOOP);
            when(server.getPluginManager()).thenReturn(pluginManager);
            when(server.getPlayer(any(UUID.class))).thenAnswer(invocation ->
                    players.getPlayer(invocation.<UUID>getArgument(0)));
        }

        private ConnectEventBrokerProcessor processor() {
            ConnectEventBrokerProcessor processor = new ConnectEventBrokerProcessor();
            processor.setBrokerServer(server);
            return processor;
        }
    }

    private static final class BlockingPlayerManager extends BrokerPlayerManager {
        private final UUID blockedPlayer;
        private final CountDownLatch addStarted = new CountDownLatch(1);
        private final CountDownLatch allowAdd = new CountDownLatch(1);

        private BlockingPlayerManager(UUID blockedPlayer) {
            this.blockedPlayer = blockedPlayer;
        }

        @Override
        public synchronized BrokerPlayer addPlayer(BrokerPlayer player) {
            if (blockedPlayer.equals(player.getUniqueId())) {
                addStarted.countDown();
                try {
                    allowAdd.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return super.addPlayer(player);
        }
    }
}
