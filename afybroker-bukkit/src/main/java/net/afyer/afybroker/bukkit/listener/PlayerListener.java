package net.afyer.afybroker.bukkit.listener;

import com.alipay.remoting.exception.RemotingException;
import net.afyer.afybroker.bukkit.AfyBroker;
import net.afyer.afybroker.core.message.PlayerServerJoinMessage;
import net.afyer.afybroker.core.observability.PlayerEventType;
import net.afyer.afybroker.core.observability.PlayerObservation;
import net.afyer.afybroker.core.session.PlayerSessionHandshake;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

/**
 * @author Nipuru
 * @since 2023/09/29 12:05
 */
public class PlayerListener implements Listener, PluginMessageListener {

    private static final int MAX_HANDSHAKE_ATTEMPTS = 40;

    private final AfyBroker plugin;

    public PlayerListener(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getBrokerClient().getObservability().onPlayer(new PlayerObservation(PlayerEventType.JOIN, Bukkit.getOnlinePlayers().size()));
        startHandshake(event.getPlayer());
    }

    public void startHandshake(Player player) {
        PlayerSessionRegistry.Binding<Player> binding = plugin.getPlayerSessions()
                .beginPending(player.getUniqueId(), player.getName(), player);
        new BukkitRunnable() {
            private int attempts;

            @Override
            public void run() {
                if (plugin.getPlayerSessions().getConnection(player) != binding || !player.isOnline()) {
                    cancel();
                    return;
                }
                if (binding.isRegistered()) {
                    cancel();
                    return;
                }
                if (attempts++ >= MAX_HANDSHAKE_ATTEMPTS) {
                    plugin.getPlayerSessions().remove(binding.getUniqueId(), player);
                    player.kickPlayer("Unable to verify player session");
                    cancel();
                    return;
                }
                player.sendPluginMessage(plugin, PlayerSessionHandshake.CHANNEL, PlayerSessionHandshake.request());
            }
        }.runTaskTimer(plugin, 1L, 5L);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!PlayerSessionHandshake.CHANNEL.equals(channel)) {
            return;
        }
        java.util.UUID sessionId = PlayerSessionHandshake.readResponse(data);
        if (sessionId == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> bindSession(player, sessionId));
    }

    private void bindSession(Player player, java.util.UUID sessionId) {
        if (!player.isOnline()) {
            return;
        }
        PlayerSessionRegistry.Binding<Player> binding = plugin.getPlayerSessions().getConnection(player);
        if (binding == null || !plugin.getPlayerSessions().bind(binding, sessionId) || binding.isRegistered()) {
            return;
        }
        if (!plugin.getPlayerSessions().accept(binding)) {
            return;
        }
        PlayerServerJoinMessage message = new PlayerServerJoinMessage()
                .setName(binding.getName())
                .setUniqueId(binding.getUniqueId())
                .setSessionId(binding.getSessionId());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getBrokerClient().oneway(message);
            } catch (RemotingException | InterruptedException e) {
                plugin.getLogger().log(Level.SEVERE, e.getMessage(), e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (plugin.getPlayerSessions().isCurrent(binding) && player.isOnline()) {
                        plugin.getPlayerSessions().remove(binding.getUniqueId(), player);
                        player.kickPlayer(null);
                    }
                });
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.getBrokerClient().getObservability().onPlayer(new PlayerObservation(PlayerEventType.LEAVE, Bukkit.getOnlinePlayers().size()));
        plugin.getPlayerSessions().remove(event.getPlayer().getUniqueId(), event.getPlayer());
    }
}
