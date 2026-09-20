package net.afyer.afybroker.server.processor.connection;

import com.alipay.remoting.Connection;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import net.afyer.afybroker.core.BrokerServiceDescriptor;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.observability.Observability;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.event.PlayerProxyLoginEvent;
import net.afyer.afybroker.server.event.PlayerProxyLogoutEvent;
import net.afyer.afybroker.server.event.PlayerServerJoinEvent;
import net.afyer.afybroker.server.plugin.Event;
import net.afyer.afybroker.server.plugin.PluginManager;
import net.afyer.afybroker.server.processor.PlayerProxyConnectBrokerProcessor;
import net.afyer.afybroker.server.processor.PlayerProxyDisconnectBrokerProcessor;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerClientManager;
import net.afyer.afybroker.server.proxy.BrokerServiceRegistry;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void proxySnapshotBeforeBackendPairsMatchingSessionAndIgnoresOldBackendSnapshot() {
        Fixture fixture = new Fixture();
        BrokerClientItem proxyB = client(fixture.clientManager, BrokerClientType.PROXY, "proxy-b");
        BrokerClientItem backendB = client(fixture.clientManager, BrokerClientType.SERVER, "backend-b");
        BrokerClientItem backendA = client(fixture.clientManager, BrokerClientType.SERVER, "backend-a");
        UUID uniqueId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        ConnectEventBrokerProcessor processor = fixture.processor();

        invoke(processor.registerPlayerBungeeCallback(proxyB), player(uniqueId, sessionB));
        invoke(processor.registerPlayerBukkitCallback(backendB), player(uniqueId, sessionB));
        invoke(processor.registerPlayerBukkitCallback(backendA), player(uniqueId, sessionA));

        BrokerPlayer current = fixture.players.getPlayer(uniqueId);
        assertSame(backendB, current.getServer());
        assertEquals(1, fixture.players.size());
    }

    @Test
    void proxySnapshotDoesNotPairBackendWhoseRpcConnectionWasReplaced() {
        Fixture fixture = new Fixture();
        BrokerClientItem backend = client(fixture.clientManager, BrokerClientType.SERVER, "backend");
        BrokerClientItem proxy = client(fixture.clientManager, BrokerClientType.PROXY, "proxy");
        UUID uniqueId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ConnectEventBrokerProcessor processor = fixture.processor();

        invoke(processor.registerPlayerBukkitCallback(backend), player(uniqueId, sessionId));
        assertEquals(1, processor.playerBukkitMap.size());
        fixture.clientManager.beginConnection(backend.getAddress(), mock(Connection.class));
        invoke(processor.registerPlayerBungeeCallback(proxy), player(uniqueId, sessionId));

        BrokerPlayer current = fixture.players.getPlayer(uniqueId);
        assertSame(proxy, current.getProxy());
        assertNull(current.getServer());
        assertTrue(processor.playerBukkitMap.isEmpty());
        verify(fixture.pluginManager, never()).callEvent(any(PlayerServerJoinEvent.class));
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

    @Test
    void sameSessionSnapshotAcrossRemoteAddressReconnectPreservesBackendWithoutDuplicateLogin() {
        Fixture fixture = new Fixture();
        BrokerClientItem oldProxy = client(fixture.clientManager, BrokerClientType.PROXY,
                "logical-proxy", "old-remote:41000");
        BrokerClientItem backend = client(fixture.clientManager, BrokerClientType.SERVER, "backend");
        UUID uniqueId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        PlayerProxyConnectBrokerProcessor connect = fixture.loginProcessor();
        PlayerProxyConnectResult login = (PlayerProxyConnectResult) connect.handleRequest(context(oldProxy),
                new PlayerProxyConnectMessage().setUniqueId(uniqueId).setSessionId(sessionId).setName("player"));
        assertTrue(login.isSuccess());
        BrokerPlayer original = fixture.players.getPlayer(uniqueId);
        original.setServer(backend);
        assertSame(oldProxy, fixture.clientManager.remove(oldProxy.getAddress(), oldProxy.getConnection()));

        BrokerClientItem newProxy = client(fixture.clientManager, BrokerClientType.PROXY,
                "logical-proxy", "new-remote:42000");
        invoke(fixture.processor().registerPlayerBungeeCallback(newProxy), player(uniqueId, sessionId));

        BrokerPlayer rebound = fixture.players.getPlayer(uniqueId);
        assertNotSame(original, rebound);
        assertSame(newProxy, rebound.getProxy());
        assertSame(backend, rebound.getServer());
        verify(fixture.pluginManager, org.mockito.Mockito.times(1)).callEvent(any(PlayerProxyLoginEvent.class));
    }

    @Test
    void emptySnapshotFromReconnectedLogicalProxyRemovesExpiredSessionAndAllowsNewLogin() {
        Fixture fixture = new Fixture();
        BrokerClientItem oldProxy = client(fixture.clientManager, BrokerClientType.PROXY,
                "logical-proxy", "old-remote:41000");
        UUID uniqueId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        PlayerProxyConnectBrokerProcessor connect = fixture.loginProcessor();
        PlayerProxyDisconnectBrokerProcessor disconnect = fixture.logoutProcessor();
        PlayerProxyConnectResult firstLogin = (PlayerProxyConnectResult) connect.handleRequest(context(oldProxy),
                new PlayerProxyConnectMessage().setUniqueId(uniqueId).setSessionId(sessionA).setName("player"));
        assertTrue(firstLogin.isSuccess());
        BrokerPlayer original = fixture.players.getPlayer(uniqueId);
        assertSame(original, fixture.players.getPlayer("player"));
        fixture.clientManager.remove(oldProxy.getAddress(), oldProxy.getConnection());
        BrokerClientItem newProxy = client(fixture.clientManager, BrokerClientType.PROXY,
                "logical-proxy", "new-remote:42000");

        // This disconnect arrives on the current RPC link while the record still belongs to the closed old link.
        disconnect.handleRequest(context(newProxy), mock(AsyncContext.class),
                new PlayerProxyDisconnectMessage().setUniqueId(uniqueId).setSessionId(sessionA).setName("player"));
        assertSame(original, fixture.players.getPlayer(uniqueId));

        fixture.processor().registerPlayerBungeeCallback(newProxy).onResponse(Collections.emptyList());
        assertNull(fixture.players.getPlayer(uniqueId));
        assertNull(fixture.players.getPlayer("player"));
        ArgumentCaptor<Event> beforeRelogin = ArgumentCaptor.forClass(Event.class);
        verify(fixture.pluginManager, org.mockito.Mockito.times(2)).callEvent(beforeRelogin.capture());
        assertTrue(beforeRelogin.getAllValues().get(0) instanceof PlayerProxyLoginEvent);
        assertTrue(beforeRelogin.getAllValues().get(1) instanceof PlayerProxyLogoutEvent);
        assertSame(original, ((PlayerProxyLogoutEvent) beforeRelogin.getAllValues().get(1)).getPlayer());

        PlayerProxyConnectResult relogin = (PlayerProxyConnectResult) connect.handleRequest(context(newProxy),
                new PlayerProxyConnectMessage().setUniqueId(uniqueId).setSessionId(sessionB).setName("player"));
        assertTrue(relogin.isSuccess());
        BrokerPlayer playerB = fixture.players.getPlayer(uniqueId);
        assertSame(playerB, fixture.players.getPlayer("player"));
        assertNotSame(original, playerB);
        ArgumentCaptor<Event> events = ArgumentCaptor.forClass(Event.class);
        verify(fixture.pluginManager, org.mockito.Mockito.times(3)).callEvent(events.capture());
        assertTrue(events.getAllValues().get(2) instanceof PlayerProxyLoginEvent);
    }

    @Test
    void emptySnapshotDoesNotRemoveDifferentProxyOrStillCurrentOldProxySession() {
        Fixture differentProxy = new Fixture();
        BrokerClientItem oldProxy = client(differentProxy.clientManager, BrokerClientType.PROXY,
                "old-logical-proxy", "old-remote:41000");
        BrokerClientItem replacement = client(differentProxy.clientManager, BrokerClientType.PROXY,
                "new-logical-proxy", "new-remote:42000");
        UUID uniqueId = UUID.randomUUID();
        BrokerPlayer oldPlayer = new BrokerPlayer(uniqueId, "player", UUID.randomUUID(), oldProxy);
        differentProxy.players.addPlayer(oldPlayer);
        differentProxy.processor().registerPlayerBungeeCallback(replacement).onResponse(Collections.emptyList());
        assertSame(oldPlayer, differentProxy.players.getPlayer(uniqueId));
        assertTrue(differentProxy.clientManager.isCurrent(oldProxy));
    }

    @Test
    void replacingLogicalProxyInvalidatesOldLinkAndLateCloseLeavesNewServiceRegistered() {
        Fixture fixture = new Fixture();
        BrokerClientItem oldProxy = client(fixture.clientManager, BrokerClientType.PROXY,
                "logical-proxy", "old-remote:41000");
        fixture.serviceRegistry.registerClientServices(oldProxy, Collections.singletonList(
                new BrokerServiceDescriptor().setServiceInterface("example.Service")));

        BrokerClientItem newProxy = client(BrokerClientType.PROXY, "new-remote:42000", mock(Connection.class));
        when(newProxy.getName()).thenReturn("LOGICAL-PROXY");
        fixture.clientManager.beginConnection(newProxy.getAddress(), newProxy.getConnection());
        BrokerClientManager.RegistrationResult result = fixture.clientManager.registerReplacingLogicalClient(newProxy);
        assertTrue(result.isRegistered());
        assertEquals(Collections.singletonList(oldProxy), result.getReplacedClients());
        assertFalse(fixture.clientManager.isCurrent(oldProxy));
        assertTrue(fixture.clientManager.isCurrent(newProxy));
        CloseEventBrokerProcessor.cleanupClient(fixture.server, oldProxy.getAddress(), oldProxy);
        fixture.serviceRegistry.registerClientServices(newProxy, Collections.singletonList(
                new BrokerServiceDescriptor().setServiceInterface("example.Service")));

        CloseEventBrokerProcessor closeProcessor = new CloseEventBrokerProcessor();
        closeProcessor.setBrokerServer(fixture.server);
        closeProcessor.onEvent(oldProxy.getAddress(), oldProxy.getConnection());

        assertNull(fixture.clientManager.getByConnection(oldProxy.getAddress(), oldProxy.getConnection()));
        assertSame(newProxy, fixture.clientManager.getByConnection(newProxy.getAddress(), newProxy.getConnection()));
        assertEquals(Collections.singletonList(newProxy), fixture.serviceRegistry.getServiceProviders(
                "example.Service", Collections.emptySet(), fixture.clientManager));
        verify(fixture.pluginManager, org.mockito.Mockito.times(1)).callEvent(any(Event.class));
    }

    @Test
    void oldCloseCleanupCannotRemoveServiceRegisteredAfterItsConnectionWasRemoved() throws Exception {
        BrokerServer server = mock(BrokerServer.class);
        CountDownLatch oldRemoved = new CountDownLatch(1);
        CountDownLatch allowOldCleanup = new CountDownLatch(1);
        BrokerClientManager manager = new BrokerClientManager() {
            @Override
            public BrokerClientItem remove(String address, Connection connection) {
                BrokerClientItem removed = super.remove(address, connection);
                if (removed != null) {
                    oldRemoved.countDown();
                    try {
                        if (!allowOldCleanup.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("old close cleanup was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                return removed;
            }
        };
        BrokerServiceRegistry serviceRegistry = new BrokerServiceRegistry();
        PluginManager pluginManager = mock(PluginManager.class);
        when(server.getClientManager()).thenReturn(manager);
        when(server.getServiceRegistry()).thenReturn(serviceRegistry);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getObservability()).thenReturn(Observability.NOOP);

        BrokerClientItem oldProxy = client(manager, BrokerClientType.PROXY, "logical-proxy", "old:41000");
        serviceRegistry.registerClientServices(oldProxy, Collections.singletonList(
                new BrokerServiceDescriptor().setServiceInterface("example.Service")));
        CloseEventBrokerProcessor closeProcessor = new CloseEventBrokerProcessor();
        closeProcessor.setBrokerServer(server);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread oldClose = new Thread(() -> {
            try {
                closeProcessor.onEvent(oldProxy.getAddress(), oldProxy.getConnection());
            } catch (Throwable e) {
                closeFailure.set(e);
            }
        });
        oldClose.start();
        assertTrue(oldRemoved.await(5, TimeUnit.SECONDS));

        BrokerClientItem newProxy = client(BrokerClientType.PROXY, "new:42000", mock(Connection.class));
        when(newProxy.getName()).thenReturn("LOGICAL-PROXY");
        manager.beginConnection(newProxy.getAddress(), newProxy.getConnection());
        assertTrue(manager.registerReplacingLogicalClient(newProxy).isRegistered());
        serviceRegistry.registerClientServices(newProxy, Collections.singletonList(
                new BrokerServiceDescriptor().setServiceInterface("example.Service")));

        allowOldCleanup.countDown();
        oldClose.join(5000);
        assertFalse(oldClose.isAlive());
        assertNull(closeFailure.get());
        assertEquals(Collections.singletonList(newProxy), serviceRegistry.getServiceProviders(
                "example.Service", Collections.emptySet(), manager));
        verify(pluginManager, org.mockito.Mockito.times(1)).callEvent(any(Event.class));
    }

    private static List<PlayerSessionInfo> replaceConnectionBeforeRows(BrokerClientManager manager,
                                                                        String address,
                                                                        Connection replacement,
                                                                        PlayerSessionInfo player) {
        return new ArrayList<PlayerSessionInfo>(Collections.singletonList(player)) {
            private void replace() {
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
            }

            @Override
            public java.util.Iterator<PlayerSessionInfo> iterator() {
                replace();
                return super.iterator();
            }

            @Override
            public void forEach(Consumer<? super PlayerSessionInfo> action) {
                replace();
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
        return client(manager, type, address, address);
    }

    private static BrokerClientItem client(BrokerClientManager manager, String type, String name, String address) {
        Connection connection = mock(Connection.class);
        BrokerClientItem client = client(type, address, connection);
        when(client.getName()).thenReturn(name);
        manager.beginConnection(address, connection);
        assertTrue(manager.registerReplacingLogicalClient(client).isRegistered());
        return client;
    }

    private static BizContext context(BrokerClientItem client) {
        BizContext context = mock(BizContext.class);
        String remoteAddress = client.getAddress();
        Connection connection = client.getConnection();
        when(context.getRemoteAddress()).thenReturn(remoteAddress);
        when(context.getConnection()).thenReturn(connection);
        return context;
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
        private final BrokerServiceRegistry serviceRegistry = new BrokerServiceRegistry();

        private Fixture() {
            this(new BrokerPlayerManager());
        }

        private Fixture(BrokerPlayerManager players) {
            this.players = players;
            when(server.getClientManager()).thenReturn(clientManager);
            when(server.getPlayerManager()).thenReturn(players);
            when(server.getObservability()).thenReturn(Observability.NOOP);
            when(server.getPluginManager()).thenReturn(pluginManager);
            when(server.getServiceRegistry()).thenReturn(serviceRegistry);
            when(server.getClient(any(BizContext.class))).thenAnswer(invocation -> {
                BizContext context = invocation.getArgument(0);
                return clientManager.getByConnection(context.getRemoteAddress(), context.getConnection());
            });
            when(server.getPlayer(any(UUID.class))).thenAnswer(invocation ->
                    players.getPlayer(invocation.<UUID>getArgument(0)));
        }

        private ConnectEventBrokerProcessor processor() {
            ConnectEventBrokerProcessor processor = new ConnectEventBrokerProcessor();
            processor.setBrokerServer(server);
            return processor;
        }

        private PlayerProxyConnectBrokerProcessor loginProcessor() {
            PlayerProxyConnectBrokerProcessor processor = new PlayerProxyConnectBrokerProcessor();
            processor.setBrokerServer(server);
            return processor;
        }

        private PlayerProxyDisconnectBrokerProcessor logoutProcessor() {
            PlayerProxyDisconnectBrokerProcessor processor = new PlayerProxyDisconnectBrokerProcessor();
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
