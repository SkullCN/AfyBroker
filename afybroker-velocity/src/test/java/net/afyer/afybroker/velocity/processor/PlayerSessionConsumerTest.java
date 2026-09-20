package net.afyer.afybroker.velocity.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.afyer.afybroker.core.message.ConnectToServerMessage;
import net.afyer.afybroker.core.message.KickPlayerMessage;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.afyer.afybroker.velocity.AfyBroker;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlayerSessionConsumerTest {

    @Test
    void staleConnectRequestDoesNotTargetReplacementPlayer() {
        UUID uniqueId = UUID.randomUUID();
        Player player = mock(Player.class);
        PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<Player> current = sessions.begin(uniqueId, "player", player);
        sessions.accept(current);
        ProxyServer proxy = mock(ProxyServer.class);
        when(proxy.getServer("lobby")).thenReturn(Optional.of(mock(RegisteredServer.class)));
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);
        when(plugin.getServer()).thenReturn(proxy);

        ConnectToServerMessage request = new ConnectToServerMessage()
                .setUniqueId(uniqueId)
                .setSessionId(UUID.randomUUID())
                .setServerName("lobby");
        new ConnectToServerVelocityProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class), request);

        verify(player, never()).createConnectionRequest(any(RegisteredServer.class));
    }

    @Test
    void staleKickRequestDoesNotDisconnectReplacementPlayer() {
        UUID uniqueId = UUID.randomUUID();
        Player player = mock(Player.class);
        PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<Player> current = sessions.begin(uniqueId, "player", player);
        sessions.accept(current);
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);

        KickPlayerMessage request = new KickPlayerMessage()
                .setUniqueId(uniqueId)
                .setSessionId(UUID.randomUUID())
                .setMessage("old session");
        new KickPlayerVelocityProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class), request);

        verify(player, never()).disconnect(any(Component.class));
    }
}
