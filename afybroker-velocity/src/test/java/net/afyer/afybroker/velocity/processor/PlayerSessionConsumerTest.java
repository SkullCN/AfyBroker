package net.afyer.afybroker.velocity.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class PlayerSessionConsumerTest {

    @Test
    void currentConnectAndKickRequestsOperateOnBoundPlayer() {
        UUID uniqueId = UUID.randomUUID();
        Player player = mock(Player.class);
        PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<Player> binding = sessions.begin(uniqueId, "player", player);
        sessions.accept(binding);
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer target = mock(RegisteredServer.class);
        ConnectionRequestBuilder connectionRequest = mock(ConnectionRequestBuilder.class);
        when(proxy.getServer("lobby")).thenReturn(Optional.of(target));
        when(player.createConnectionRequest(target)).thenReturn(connectionRequest);
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);
        when(plugin.getServer()).thenReturn(proxy);

        new ConnectToServerVelocityProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                new ConnectToServerMessage().setUniqueId(uniqueId).setSessionId(binding.getSessionId()).setServerName("lobby"));
        new KickPlayerVelocityProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                new KickPlayerMessage().setUniqueId(uniqueId).setSessionId(binding.getSessionId()).setMessage("maintenance"));

        verify(player).createConnectionRequest(target);
        verify(connectionRequest).connect();
        verify(player).disconnect(Component.text("maintenance"));
    }

    @Test
    void connectValidatedOnAThenMappingReplacementStillOperatesOnCapturedAOnly() {
        UUID uniqueId = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        Player playerA = mock(Player.class);
        Player playerB = mock(Player.class);
        PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<Player> bindingA = sessions.begin(uniqueId, "player", playerA);
        sessions.accept(bindingA);
        ProxyServer proxy = mock(ProxyServer.class);
        RegisteredServer target = mock(RegisteredServer.class);
        ConnectionRequestBuilder requestA = mock(ConnectionRequestBuilder.class);
        when(proxy.getServer("lobby")).thenReturn(Optional.of(target));
        when(playerA.createConnectionRequest(target)).thenAnswer(invocation -> {
            PlayerSessionRegistry.Binding<Player> bindingB = sessions.begin(uniqueId, "player", playerB);
            sessions.bind(bindingB, sessionB);
            sessions.accept(bindingB);
            return requestA;
        });
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);
        when(plugin.getServer()).thenReturn(proxy);

        new ConnectToServerVelocityProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                new ConnectToServerMessage().setUniqueId(uniqueId).setSessionId(bindingA.getSessionId()).setServerName("lobby"));

        assertSame(playerB, sessions.getCurrent(uniqueId).getPlayer());
        verify(playerA).createConnectionRequest(target);
        verify(requestA).connect();
        verify(playerB, never()).createConnectionRequest(any(RegisteredServer.class));
    }

    @Test
    void kickValidatedOnAThenMappingReplacementStillDisconnectsCapturedAOnly() {
        UUID uniqueId = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        Player playerA = mock(Player.class);
        Player playerB = mock(Player.class);
        PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<Player> bindingA = sessions.begin(uniqueId, "player", playerA);
        sessions.accept(bindingA);
        doAnswer(invocation -> {
            PlayerSessionRegistry.Binding<Player> bindingB = sessions.begin(uniqueId, "player", playerB);
            sessions.bind(bindingB, sessionB);
            sessions.accept(bindingB);
            return null;
        }).when(playerA).disconnect(any(Component.class));
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);

        new KickPlayerVelocityProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                new KickPlayerMessage().setUniqueId(uniqueId).setSessionId(bindingA.getSessionId()).setMessage("maintenance"));

        assertSame(playerB, sessions.getCurrent(uniqueId).getPlayer());
        verify(playerA).disconnect(Component.text("maintenance"));
        verify(playerB, never()).disconnect(any(Component.class));
    }

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
