package net.afyer.afybroker.bungee.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import net.afyer.afybroker.bungee.AfyBroker;
import net.afyer.afybroker.core.message.ConnectToServerMessage;
import net.afyer.afybroker.core.message.KickPlayerMessage;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlayerSessionConsumerTest {

    @Test
    void staleConnectRequestDoesNotTargetReplacementPlayer() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        PlayerSessionRegistry<ProxiedPlayer> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<ProxiedPlayer> current = sessions.begin(uniqueId, "player", player);
        sessions.accept(current);
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getServerInfo("lobby")).thenReturn(mock(ServerInfo.class));
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);
        when(plugin.getProxy()).thenReturn(proxy);

        ConnectToServerMessage request = new ConnectToServerMessage()
                .setUniqueId(uniqueId)
                .setSessionId(UUID.randomUUID())
                .setServerName("lobby");
        new ConnectToServerBungeeProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class), request);

        verify(player, never()).connect(any(ServerInfo.class));
    }

    @Test
    void staleKickRequestDoesNotDisconnectReplacementPlayer() {
        UUID uniqueId = UUID.randomUUID();
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        PlayerSessionRegistry<ProxiedPlayer> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<ProxiedPlayer> current = sessions.begin(uniqueId, "player", player);
        sessions.accept(current);
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);

        KickPlayerMessage request = new KickPlayerMessage()
                .setUniqueId(uniqueId)
                .setSessionId(UUID.randomUUID())
                .setMessage("old session");
        new KickPlayerBungeeProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class), request);

        verify(player, never()).disconnect(any(net.md_5.bungee.api.chat.BaseComponent[].class));
        verify(player, never()).disconnect();
    }
}
