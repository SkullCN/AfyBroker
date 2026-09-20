package net.afyer.afybroker.bungee.listener;

import com.alipay.remoting.exception.RemotingException;
import net.afyer.afybroker.bungee.AfyBroker;
import net.afyer.afybroker.client.BrokerClient;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.message.PlayerServerConnectedMessage;
import net.afyer.afybroker.core.observability.PlayerEventType;
import net.afyer.afybroker.core.observability.PlayerObservation;
import net.afyer.afybroker.core.session.PlayerSessionHandshake;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.logging.Level;

/**
 * @author Nipuru
 * @since 2022/7/30 18:44
 */
public class PlayerListener implements Listener {

    private final AfyBroker plugin;

    public PlayerListener(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = Byte.MAX_VALUE)
    public void onConnect(ServerConnectEvent event) {
        if (event.getReason() != ServerConnectEvent.Reason.JOIN_PROXY) {
            return;
        }

        ProxiedPlayer player = event.getPlayer();
        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = plugin.getPlayerSessions()
                .begin(player.getUniqueId(), player.getName(), player);
        ServerInfo initialServer = event.getTarget();
        PlayerProxyConnectMessage connectMessage = new PlayerProxyConnectMessage()
                .setUniqueId(player.getUniqueId())
                .setSessionId(binding.getSessionId())
                .setName(player.getName())
                .setServerName(initialServer.getName());

        try {
            PlayerProxyConnectResult result = plugin.getBrokerClient().invokeSync(connectMessage);
            if (!result.isSuccess()) {
                rejectPending(binding, event, "Login failed");
                return;
            }

            if (!isPendingCurrent(binding)) {
                sendDisconnect(binding);
                return;
            }
            if (!plugin.getPlayerSessions().accept(binding)) {
                sendDisconnect(binding);
                return;
            }
            plugin.getBrokerClient().getObservability().onPlayer(new PlayerObservation(
                    PlayerEventType.JOIN, ProxyServer.getInstance().getOnlineCount()));

            String serverName = result.getServerName();
            ServerInfo target = serverName == null || serverName.trim().isEmpty()
                    ? null : ProxyServer.getInstance().getServerInfo(serverName);
            if (target == null) {
                rejectAccepted(binding, event, "Login failed");
                return;
            }
            event.setTarget(target);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, ex.getMessage(), ex);
            rejectPending(binding, event, "An error occurred");
            sendDisconnect(binding);
        }
    }

    private boolean isPendingCurrent(PlayerSessionRegistry.Binding<ProxiedPlayer> binding) {
        return plugin.getPlayerSessions().getConnection(binding.getPlayer()) == binding
                && ProxyServer.getInstance().getPlayer(binding.getUniqueId()) == binding.getPlayer();
    }

    private void rejectPending(PlayerSessionRegistry.Binding<ProxiedPlayer> binding,
                               ServerConnectEvent event, String message) {
        if (plugin.getPlayerSessions().getConnection(binding.getPlayer()) == binding) {
            plugin.getPlayerSessions().remove(binding.getUniqueId(), binding.getPlayer());
            if (ProxyServer.getInstance().getPlayer(binding.getUniqueId()) == binding.getPlayer()) {
                disconnect(event, message);
            }
        }
    }

    private void rejectAccepted(PlayerSessionRegistry.Binding<ProxiedPlayer> binding,
                                ServerConnectEvent event, String message) {
        if (plugin.getPlayerSessions().isCurrent(binding)) {
            plugin.getPlayerSessions().remove(binding.getUniqueId(), binding.getPlayer());
            sendDisconnect(binding);
            disconnect(event, message);
        }
    }

    private void sendDisconnect(PlayerSessionRegistry.Binding<ProxiedPlayer> binding) {
        PlayerProxyDisconnectMessage message = new PlayerProxyDisconnectMessage()
                .setUniqueId(binding.getUniqueId())
                .setSessionId(binding.getSessionId())
                .setName(binding.getName());
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                plugin.getBrokerClient().oneway(message);
            } catch (RemotingException | InterruptedException e) {
                plugin.getLogger().log(Level.SEVERE, e.getMessage(), e);
            }
        });
    }

    @EventHandler
    public void onDisConnect(PlayerDisconnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = plugin.getPlayerSessions()
                .get(player.getUniqueId(), player);
        boolean accepted = binding != null && plugin.getPlayerSessions().isCurrent(binding);
        if (plugin.getPlayerSessions().remove(player.getUniqueId(), player) == null || !accepted) {
            return;
        }
        plugin.getBrokerClient().getObservability().onPlayer(new PlayerObservation(PlayerEventType.LEAVE, ProxyServer.getInstance().getOnlineCount()));
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            PlayerProxyDisconnectMessage msg = new PlayerProxyDisconnectMessage()
                    .setUniqueId(player.getUniqueId())
                    .setSessionId(binding.getSessionId())
                    .setName(player.getName());

            try {
                plugin.getBrokerClient().oneway(msg);
            } catch (RemotingException | InterruptedException e) {
                plugin.getLogger().log(Level.SEVERE, e.getMessage(), e);
            }
        });
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        BrokerClient brokerClient = plugin.getBrokerClient();
        ProxiedPlayer player = event.getPlayer();
        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = plugin.getPlayerSessions()
                .get(player.getUniqueId(), player);
        if (binding == null || !plugin.getPlayerSessions().isCurrent(binding)) {
            return;
        }

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            PlayerServerConnectedMessage msg = new PlayerServerConnectedMessage()
                    .setName(player.getName())
                    .setUniqueId(player.getUniqueId())
                    .setSessionId(binding.getSessionId())
                    .setServerName(event.getServer().getInfo().getName());

            try {
                brokerClient.oneway(msg);
            } catch (RemotingException | InterruptedException e) {
                plugin.getLogger().log(Level.SEVERE, e.getMessage(), e);
            }
        });
    }

    @EventHandler(priority = Byte.MAX_VALUE)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!PlayerSessionHandshake.CHANNEL.equals(event.getTag())) {
            return;
        }
        event.setCancelled(true);
        if (!PlayerSessionHandshake.isRequest(event.getData())
                || !(event.getSender() instanceof Server)
                || !(event.getReceiver() instanceof ProxiedPlayer)) {
            return;
        }
        Server source = (Server) event.getSender();
        ProxiedPlayer player = (ProxiedPlayer) event.getReceiver();
        if (player.getServer() != source) {
            return;
        }
        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = plugin.getPlayerSessions()
                .get(player.getUniqueId(), player);
        if (binding != null && plugin.getPlayerSessions().isCurrent(binding)) {
            source.sendData(PlayerSessionHandshake.CHANNEL, PlayerSessionHandshake.response(binding.getSessionId()));
        }
    }

    private void disconnect(ServerConnectEvent event, String message) {
        event.setCancelled(true);
        event.getPlayer().disconnect(new TextComponent(message));
    }
}
