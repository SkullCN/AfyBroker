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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PlayerSessionConsumerTest {

    private Field pendingConnectField;
    private Field processorPendingConnectField;

    @BeforeEach
    void exposePendingConnectCollectionOnTestPlayer() throws Exception {
        pendingConnectField = FieldWithPendingConnects.class.getDeclaredField("pendingConnects");
        pendingConnectField.setAccessible(true);
        processorPendingConnectField = ConnectToServerBungeeProcessor.class.getDeclaredField("PENDING_CONNECT_FIELD");
        processorPendingConnectField.setAccessible(true);
        processorPendingConnectField.set(null, pendingConnectField);
    }

    @AfterEach
    void clearPendingConnectFieldCache() throws Exception {
        processorPendingConnectField.set(null, null);
    }

    @Test
    void currentConnectAndKickRequestsOperateOnBoundPlayer() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        FieldWithPendingConnects player = mock(FieldWithPendingConnects.class);
        pendingConnectField.set(player, new ArrayList<ServerInfo>());
        PlayerSessionRegistry<ProxiedPlayer> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = sessions.begin(uniqueId, "player", player);
        sessions.accept(binding);
        ProxyServer proxy = mock(ProxyServer.class);
        ServerInfo target = mock(ServerInfo.class);
        when(proxy.getServerInfo("lobby")).thenReturn(target);
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);
        when(plugin.getProxy()).thenReturn(proxy);

        new ConnectToServerBungeeProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                new ConnectToServerMessage().setUniqueId(uniqueId).setSessionId(binding.getSessionId())
                        .setServerName("lobby"));
        new KickPlayerBungeeProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                new KickPlayerMessage().setUniqueId(uniqueId).setSessionId(binding.getSessionId())
                        .setMessage("maintenance"));

        verify(player).connect(target);
        verify(player).disconnect(any(net.md_5.bungee.api.chat.BaseComponent[].class));
    }

    @Test
    void connectValidatedOnAThenMappingReplacementStillOperatesOnCapturedAOnly() throws Exception {
        UUID uniqueId = UUID.randomUUID();
        FieldWithPendingConnects playerA = mock(FieldWithPendingConnects.class);
        FieldWithPendingConnects playerB = mock(FieldWithPendingConnects.class);
        pendingConnectField.set(playerA, new ArrayList<ServerInfo>());
        pendingConnectField.set(playerB, new ArrayList<ServerInfo>());
        PlayerSessionRegistry<ProxiedPlayer> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<ProxiedPlayer> bindingA = sessions.begin(uniqueId, "player", playerA);
        sessions.accept(bindingA);
        CountDownLatch inServerLookup = new CountDownLatch(1);
        CountDownLatch allowLookup = new CountDownLatch(1);
        when(playerA.getServer()).thenAnswer(invocation -> {
            inServerLookup.countDown();
            allowLookup.await(5, TimeUnit.SECONDS);
            return null;
        });
        ProxyServer proxy = mock(ProxyServer.class);
        ServerInfo target = mock(ServerInfo.class);
        when(proxy.getServerInfo("lobby")).thenReturn(target);
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);
        when(plugin.getProxy()).thenReturn(proxy);
        ConnectToServerBungeeProcessor processor = new ConnectToServerBungeeProcessor(plugin);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread operation = new Thread(() -> {
            try {
                processor.handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                        new ConnectToServerMessage().setUniqueId(uniqueId)
                                .setSessionId(bindingA.getSessionId()).setServerName("lobby"));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        operation.start();
        assertTrue(inServerLookup.await(5, TimeUnit.SECONDS));
        PlayerSessionRegistry.Binding<ProxiedPlayer> bindingB = sessions.begin(uniqueId, "player", playerB);
        sessions.accept(bindingB);
        allowLookup.countDown();
        operation.join(5000);

        assertFalse(operation.isAlive());
        assertNull(failure.get());
        assertSame(playerB, sessions.getCurrent(uniqueId).getPlayer());
        verify(playerA).connect(target);
        verify(playerB, never()).connect(any(ServerInfo.class));
    }

    @Test
    void kickValidatedOnAThenMappingReplacementStillDisconnectsCapturedAOnly() {
        UUID uniqueId = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        ProxiedPlayer playerA = mock(ProxiedPlayer.class);
        ProxiedPlayer playerB = mock(ProxiedPlayer.class);
        PlayerSessionRegistry<ProxiedPlayer> sessions = new PlayerSessionRegistry<>();
        PlayerSessionRegistry.Binding<ProxiedPlayer> bindingA = sessions.begin(uniqueId, "player", playerA);
        sessions.accept(bindingA);
        doAnswer(invocation -> {
            PlayerSessionRegistry.Binding<ProxiedPlayer> bindingB = sessions.begin(uniqueId, "player", playerB);
            sessions.bind(bindingB, sessionB);
            sessions.accept(bindingB);
            return null;
        }).when(playerA).disconnect(any(net.md_5.bungee.api.chat.BaseComponent[].class));
        AfyBroker plugin = mock(AfyBroker.class);
        when(plugin.getPlayerSessions()).thenReturn(sessions);

        new KickPlayerBungeeProcessor(plugin).handleRequest(mock(BizContext.class), mock(AsyncContext.class),
                new KickPlayerMessage().setUniqueId(uniqueId).setSessionId(bindingA.getSessionId())
                        .setMessage("maintenance"));

        assertSame(playerB, sessions.getCurrent(uniqueId).getPlayer());
        verify(playerA).disconnect(any(net.md_5.bungee.api.chat.BaseComponent[].class));
        verify(playerB, never()).disconnect(any(net.md_5.bungee.api.chat.BaseComponent[].class));
    }

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

    private abstract static class FieldWithPendingConnects implements ProxiedPlayer {
        @SuppressWarnings("unused")
        private final Collection<ServerInfo> pendingConnects = new ArrayList<>();
    }
}
