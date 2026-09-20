package net.afyer.afybroker.server.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.Connection;
import com.alipay.remoting.rpc.RpcServer;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.BrokerClientInfoMessage;
import net.afyer.afybroker.core.message.ConnectToServerMessage;
import net.afyer.afybroker.core.message.KickPlayerMessage;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerClientManager;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerOperationBrokerProcessorTest {

    @Test
    void registeredServerCanForwardCurrentSessionButNotDelayedOldSession() throws Exception {
        BrokerClientManager clients = new BrokerClientManager();
        BrokerPlayerManager players = new BrokerPlayerManager();
        RpcServer proxyRpc = mock(RpcServer.class);
        BrokerClientItem proxy = client(clients, BrokerClientType.PROXY, "proxy:9000", proxyRpc);
        BrokerClientItem backend = client(clients, BrokerClientType.SERVER, "backend:9000", mock(RpcServer.class));
        Connection backendConnection = backend.getConnection();
        BizContext registeredBackend = context("backend:9000", backendConnection);
        BizContext unknownBackend = context("backend:other", mock(Connection.class));
        BrokerServer server = mock(BrokerServer.class);
        when(server.getClientManager()).thenReturn(clients);
        when(server.getPlayerManager()).thenReturn(players);
        when(server.getPlayer(any(UUID.class))).thenAnswer(invocation ->
                players.getPlayer(invocation.<UUID>getArgument(0)));
        when(server.getClient(registeredBackend)).thenAnswer(invocation ->
                clients.getByConnection("backend:9000", backendConnection));
        when(server.getClient(unknownBackend)).thenAnswer(invocation ->
                clients.getByConnection("backend:other", unknownBackend.getConnection()));

        UUID uniqueId = UUID.randomUUID();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        players.addPlayer(new BrokerPlayer(uniqueId, "player", sessionB, proxy));
        ConnectToServerBrokerProcessor connect = new ConnectToServerBrokerProcessor();
        connect.setBrokerServer(server);
        KickPlayerBrokerProcessor kick = new KickPlayerBrokerProcessor();
        kick.setBrokerServer(server);

        connect.handleRequest(registeredBackend, mock(AsyncContext.class), new ConnectToServerMessage()
                .setUniqueId(uniqueId).setSessionId(sessionB).setServerName("lobby"));
        kick.handleRequest(registeredBackend, mock(AsyncContext.class), new KickPlayerMessage()
                .setUniqueId(uniqueId).setSessionId(sessionB).setMessage("kick"));

        ArgumentCaptor<Object> forwarded = ArgumentCaptor.forClass(Object.class);
        verify(proxyRpc, org.mockito.Mockito.times(2)).oneway(eq("proxy:9000"), forwarded.capture());
        assertInstanceOf(ConnectToServerMessage.class, forwarded.getAllValues().get(0));
        assertInstanceOf(KickPlayerMessage.class, forwarded.getAllValues().get(1));
        assertEquals(sessionB, ((ConnectToServerMessage) forwarded.getAllValues().get(0)).getSessionId());
        assertEquals(sessionB, ((KickPlayerMessage) forwarded.getAllValues().get(1)).getSessionId());

        connect.handleRequest(registeredBackend, mock(AsyncContext.class), new ConnectToServerMessage()
                .setUniqueId(uniqueId).setSessionId(sessionA).setServerName("lobby"));
        kick.handleRequest(registeredBackend, mock(AsyncContext.class), new KickPlayerMessage()
                .setUniqueId(uniqueId).setSessionId(sessionA).setMessage("old session"));
        connect.handleRequest(unknownBackend, mock(AsyncContext.class), new ConnectToServerMessage()
                .setUniqueId(uniqueId).setSessionId(sessionB).setServerName("lobby"));

        verify(proxyRpc, org.mockito.Mockito.times(2)).oneway(eq("proxy:9000"), any());
    }

    private static BrokerClientItem client(BrokerClientManager manager, String type, String address,
                                           RpcServer rpcServer) {
        Connection connection = mock(Connection.class);
        BrokerClientItem client = new BrokerClientItem(new BrokerClientInfoMessage()
                .setName(address)
                .setType(type)
                .setAddress(address)
                .setTags(Collections.emptySet())
                .setMetadata(Collections.emptyMap())
                .setServices(Collections.emptyList()), rpcServer, Collections.emptyList(), connection);
        manager.beginConnection(address, connection);
        if (!manager.register(client)) {
            throw new AssertionError("client registration failed");
        }
        return client;
    }

    private static BizContext context(String address, Connection connection) {
        BizContext context = mock(BizContext.class);
        when(context.getRemoteAddress()).thenReturn(address);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }
}
