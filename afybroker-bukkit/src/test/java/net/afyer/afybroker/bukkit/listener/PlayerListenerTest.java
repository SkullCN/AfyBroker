package net.afyer.afybroker.bukkit.listener;

import net.afyer.afybroker.bukkit.AfyBroker;
import net.afyer.afybroker.client.BrokerClient;
import net.afyer.afybroker.core.message.PlayerServerJoinMessage;
import net.afyer.afybroker.core.observability.Observability;
import net.afyer.afybroker.core.session.PlayerSessionHandshake;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginLogger;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayerListenerTest {

    private static final AtomicReference<BukkitScheduler> ACTIVE_SCHEDULER = new AtomicReference<>();

    @BeforeAll
    static void installBukkitScheduler() {
        Server server = mock(Server.class);
        when(server.getLogger()).thenReturn(Logger.getLogger("test-bukkit"));
        when(server.getName()).thenReturn("test");
        when(server.getVersion()).thenReturn("test");
        when(server.getBukkitVersion()).thenReturn("1.12.2");
        when(server.getScheduler()).thenAnswer(invocation -> ACTIVE_SCHEDULER.get());
        Bukkit.setServer(server);
    }

    @Test
    void exactBackendReplyBindsPlayerOnMainQueueAndPublishesOneJoinAsynchronously() throws Exception {
        Fixture fixture = new Fixture();
        fixture.listener.startHandshake(fixture.player);
        fixture.timer(0).run();
        verify(fixture.player).sendPluginMessage(eq(fixture.plugin), eq(PlayerSessionHandshake.CHANNEL),
                eq(PlayerSessionHandshake.request()));

        UUID sessionId = UUID.randomUUID();
        fixture.listener.onPluginMessageReceived(PlayerSessionHandshake.CHANNEL, fixture.player,
                PlayerSessionHandshake.response(sessionId));
        assertEquals(1, fixture.mainTasks.size());
        assertNull(fixture.sessions.getCurrent(fixture.uniqueId));
        fixture.runMain();

        PlayerSessionRegistry.Binding<Player> binding = fixture.sessions.getCurrent(fixture.uniqueId);
        assertNotNull(binding);
        assertSame(fixture.player, binding.getPlayer());
        assertEquals(sessionId, binding.getSessionId());
        assertTrue(binding.isRegistered());
        verify(fixture.brokerClient, never()).oneway(any());

        fixture.runAsync();
        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(fixture.brokerClient).oneway(message.capture());
        assertInstanceOf(PlayerServerJoinMessage.class, message.getValue());
        PlayerServerJoinMessage join = (PlayerServerJoinMessage) message.getValue();
        assertEquals(fixture.uniqueId, join.getUniqueId());
        assertEquals(sessionId, join.getSessionId());
        assertEquals("player", join.getName());

        fixture.listener.onPluginMessageReceived(PlayerSessionHandshake.CHANNEL, fixture.player,
                PlayerSessionHandshake.response(UUID.randomUUID()));
        fixture.runMain();
        assertTrue(fixture.mainTasks.isEmpty());
        verify(fixture.brokerClient, times(1)).oneway(any());
    }

    @Test
    void lateAReplyTimeoutAndQuitCannotRemovePlayerB() throws Exception {
        Fixture fixture = new Fixture();
        Player playerA = fixture.player("A");
        Player playerB = fixture.player("B");
        fixture.listener.startHandshake(playerA);
        Runnable timeoutA = fixture.timer(0);
        fixture.listener.startHandshake(playerB);
        Runnable timeoutB = fixture.timer(1);
        PlayerSessionRegistry.Binding<Player> pendingB = fixture.sessions.getConnection(playerB);
        UUID sessionB = UUID.randomUUID();

        fixture.listener.onPluginMessageReceived(PlayerSessionHandshake.CHANNEL, playerA,
                PlayerSessionHandshake.response(UUID.randomUUID()));
        fixture.runMain();
        assertNull(fixture.sessions.getCurrent(fixture.uniqueId));

        fixture.listener.onQuit(new PlayerQuitEvent(playerA, ""));
        timeoutA.run();
        verify(playerA, never()).kickPlayer(anyString());
        assertSame(pendingB, fixture.sessions.getConnection(playerB));

        fixture.listener.onPluginMessageReceived(PlayerSessionHandshake.CHANNEL, playerB,
                PlayerSessionHandshake.response(sessionB));
        fixture.runMain();
        assertEquals(sessionB, fixture.sessions.getCurrent(fixture.uniqueId).getSessionId());
        timeoutB.run();
        verify(playerB, never()).kickPlayer(anyString());
        fixture.runAsync();

        assertSame(playerB, fixture.sessions.getCurrent(fixture.uniqueId).getPlayer());
        verify(fixture.brokerClient, times(1)).oneway(any());
    }

    @Test
    void lateSendFailureFromAAfterBIsAcceptedCannotKickB() throws Exception {
        Fixture fixture = new Fixture();
        Player playerA = fixture.player("A");
        Player playerB = fixture.player("B");
        fixture.listener.startHandshake(playerA);
        UUID sessionA = UUID.randomUUID();
        fixture.listener.onPluginMessageReceived(PlayerSessionHandshake.CHANNEL, playerA,
                PlayerSessionHandshake.response(sessionA));
        fixture.runMain();
        Runnable sendA = fixture.asyncTasks.remove(0);

        fixture.listener.startHandshake(playerB);
        fixture.listener.onQuit(new PlayerQuitEvent(playerA, ""));
        UUID sessionB = UUID.randomUUID();
        fixture.listener.onPluginMessageReceived(PlayerSessionHandshake.CHANNEL, playerB,
                PlayerSessionHandshake.response(sessionB));
        fixture.runMain();

        doAnswer(invocation -> {
            Object request = invocation.getArgument(0);
            if (request instanceof PlayerServerJoinMessage
                    && sessionA.equals(((PlayerServerJoinMessage) request).getSessionId())) {
                throw new InterruptedException("simulated late send failure");
            }
            return null;
        }).when(fixture.brokerClient).oneway(any());
        sendA.run();
        fixture.runMain();

        verify(playerA, never()).kickPlayer(anyString());
        verify(playerB, never()).kickPlayer(anyString());
        assertSame(playerB, fixture.sessions.getCurrent(fixture.uniqueId).getPlayer());
        fixture.runAsync();
        verify(fixture.brokerClient, times(2)).oneway(any());
    }

    private static final class Fixture {
        private final UUID uniqueId = UUID.randomUUID();
        private final AfyBroker plugin = mock(AfyBroker.class);
        private final BrokerClient brokerClient = mock(BrokerClient.class);
        private final PlayerSessionRegistry<Player> sessions = new PlayerSessionRegistry<>();
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final List<Runnable> timers = new ArrayList<>();
        private final List<Runnable> mainTasks = new ArrayList<>();
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final Player player = player("player");
        private final PlayerListener listener;

        private Fixture() {
            ACTIVE_SCHEDULER.set(scheduler);
            when(plugin.getPlayerSessions()).thenReturn(sessions);
            when(plugin.getBrokerClient()).thenReturn(brokerClient);
            setLogger(plugin);
            when(brokerClient.getObservability()).thenReturn(mock(Observability.class));

            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(5L)))
                    .thenAnswer(invocation -> {
                        timers.add(invocation.getArgument(1));
                        return mock(BukkitTask.class);
                    });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                mainTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            });
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                asyncTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            });
            listener = new PlayerListener(plugin);
        }

        private Player player(String name) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(uniqueId);
            when(player.getName()).thenReturn(name);
            when(player.isOnline()).thenReturn(true);
            return player;
        }

        private Runnable timer(int index) {
            return timers.get(index);
        }

        private void runMain() {
            assertFalse(mainTasks.isEmpty());
            mainTasks.remove(0).run();
        }

        private void runAsync() {
            assertFalse(asyncTasks.isEmpty());
            asyncTasks.remove(0).run();
        }

        private static void setLogger(AfyBroker plugin) {
            try {
                Field logger = JavaPlugin.class.getDeclaredField("logger");
                logger.setAccessible(true);
                logger.set(plugin, mock(PluginLogger.class));
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
