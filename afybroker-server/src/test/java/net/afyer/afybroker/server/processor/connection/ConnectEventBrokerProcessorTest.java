package net.afyer.afybroker.server.processor.connection;

import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.Connection;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.observability.Observability;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.plugin.PluginManager;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerClientManager;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectEventBrokerProcessorTest {

    @Test
    void delayedOldBackendSnapshotDoesNotDiscardCurrentSessionBackend() {
        BrokerServer server = mock(BrokerServer.class);
        BrokerClientManager clientManager = mock(BrokerClientManager.class);
        BrokerPlayerManager playerManager = new BrokerPlayerManager();
        when(server.getClientManager()).thenReturn(clientManager);
        when(server.getPlayerManager()).thenReturn(playerManager);
        when(server.getObservability()).thenReturn(Observability.NOOP);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(clientManager.isCurrent(any(BrokerClientItem.class))).thenReturn(true);

        BrokerClientItem backendB = client(BrokerClientType.SERVER);
        BrokerClientItem backendA = client(BrokerClientType.SERVER);
        BrokerClientItem proxyB = client(BrokerClientType.PROXY);
        UUID uniqueId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        PlayerSessionInfo playerA = player(uniqueId, sessionA);
        PlayerSessionInfo playerB = player(uniqueId, sessionB);
        ConnectEventBrokerProcessor processor = new ConnectEventBrokerProcessor();
        processor.setBrokerServer(server);

        // Broker restart receives backend B, then an older A snapshot, before the active proxy snapshot.
        invoke(processor.registerPlayerBukkitCallback(backendB), playerB);
        invoke(processor.registerPlayerBukkitCallback(backendA), playerA);
        invoke(processor.registerPlayerBungeeCallback(proxyB), playerB);

        BrokerPlayer current = playerManager.getPlayer(uniqueId);
        assertSame(backendB, current.getServer());

        // A backend snapshot arriving after B was established is discarded as stale.
        invoke(processor.registerPlayerBukkitCallback(backendA), playerA);
        assertSame(backendB, current.getServer());
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
        BrokerClientItem oldProxy = client(BrokerClientType.PROXY);
        BrokerClientItem newProxy = client(BrokerClientType.PROXY);
        BrokerClientItem backend = client(BrokerClientType.SERVER);
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

    private static void invoke(InvokeCallback callback, PlayerSessionInfo player) {
        callback.onResponse(Collections.singletonList(player));
    }

    private static PlayerSessionInfo player(UUID uniqueId, UUID sessionId) {
        return new PlayerSessionInfo()
                .setUniqueId(uniqueId)
                .setSessionId(sessionId)
                .setName("player");
    }

    private static BrokerClientItem client(String type) {
        BrokerClientItem client = mock(BrokerClientItem.class);
        when(client.getType()).thenReturn(type);
        return client;
    }

    private static BrokerClientItem client(String type, String address, Connection connection) {
        BrokerClientItem client = client(type);
        when(client.getAddress()).thenReturn(address);
        when(client.getConnection()).thenReturn(connection);
        return client;
    }
}
