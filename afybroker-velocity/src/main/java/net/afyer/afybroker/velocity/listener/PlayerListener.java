package net.afyer.afybroker.velocity.listener;

import com.alipay.remoting.exception.RemotingException;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.message.PlayerServerConnectedMessage;
import net.afyer.afybroker.core.observability.PlayerEventType;
import net.afyer.afybroker.core.observability.PlayerObservation;
import net.afyer.afybroker.core.session.PlayerSessionHandshake;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.afyer.afybroker.velocity.AfyBroker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;


/**
 * @author Nipuru
 * @since 2022/7/30 18:44
 */
public class PlayerListener {

    private final AfyBroker plugin;

    public PlayerListener(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @Subscribe(order = PostOrder.LAST)
    public EventTask onConnect(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        PlayerSessionRegistry.Binding<Player> binding = plugin.getPlayerSessions()
                .begin(player.getUniqueId(), player.getUsername(), player);
        return EventTask.async(() -> handleInitialServer(event, binding));
    }

    private void handleInitialServer(PlayerChooseInitialServerEvent event,
                                     PlayerSessionRegistry.Binding<Player> binding) {
        Player player = binding.getPlayer();
        if (!isPendingCurrent(binding) || !player.isActive()) {
            return;
        }
        String initialServerName = event.getInitialServer()
                .map(server -> server.getServerInfo().getName())
                .orElse(null);
        PlayerProxyConnectMessage connectMessage = new PlayerProxyConnectMessage()
                .setUniqueId(player.getUniqueId())
                .setSessionId(binding.getSessionId())
                .setName(player.getUsername())
                .setServerName(initialServerName);

        try {
            PlayerProxyConnectResult result = plugin.getBrokerClient().invokeSync(connectMessage);
            if (!result.isSuccess()) {
                rejectPending(binding, "Login failed");
                return;
            }

            if (!isPendingCurrent(binding) || !player.isActive()) {
                sendDisconnect(binding);
                return;
            }
            if (!plugin.getPlayerSessions().accept(binding)) {
                sendDisconnect(binding);
                return;
            }
            plugin.getBrokerClient().getObservability().onPlayer(new PlayerObservation(
                    PlayerEventType.JOIN, plugin.getServer().getPlayerCount()));

            String serverName = result.getServerName();
            RegisteredServer target = serverName == null || serverName.trim().isEmpty()
                    ? null : plugin.getServer().getServer(serverName).orElse(null);
            if (target == null) {
                rejectAccepted(binding, "Login failed");
                return;
            }
            event.setInitialServer(target);
        } catch (Exception e) {
            plugin.getLogger().error(e.getMessage(), e);
            rejectPending(binding, "An error occurred");
            sendDisconnect(binding);
        }
    }

    private boolean isPendingCurrent(PlayerSessionRegistry.Binding<Player> binding) {
        return plugin.getPlayerSessions().getConnection(binding.getPlayer()) == binding;
    }

    private void rejectPending(PlayerSessionRegistry.Binding<Player> binding, String message) {
        Player player = binding.getPlayer();
        if (plugin.getPlayerSessions().getConnection(player) == binding) {
            plugin.getPlayerSessions().remove(binding.getUniqueId(), player);
            if (player.isActive()) {
                disconnect(player, message);
            }
        }
    }

    private void rejectAccepted(PlayerSessionRegistry.Binding<Player> binding, String message) {
        Player player = binding.getPlayer();
        if (plugin.getPlayerSessions().isCurrent(binding)) {
            plugin.getPlayerSessions().remove(binding.getUniqueId(), player);
            sendDisconnect(binding);
            if (player.isActive()) {
                disconnect(player, message);
            }
        }
    }

    private void sendDisconnect(PlayerSessionRegistry.Binding<Player> binding) {
        PlayerProxyDisconnectMessage message = new PlayerProxyDisconnectMessage()
                .setUniqueId(binding.getUniqueId())
                .setSessionId(binding.getSessionId())
                .setName(binding.getName());
        try {
            plugin.getBrokerClient().oneway(message);
        } catch (RemotingException | InterruptedException e) {
            plugin.getLogger().error(e.getMessage(), e);
        }
    }

    @Subscribe
    public void onDisConnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        PlayerSessionRegistry.Binding<Player> binding = plugin.getPlayerSessions()
                .get(player.getUniqueId(), player);
        boolean accepted = binding != null && plugin.getPlayerSessions().isCurrent(binding);
        if (plugin.getPlayerSessions().remove(player.getUniqueId(), player) == null || !accepted) {
            return;
        }
        plugin.getBrokerClient().getObservability().onPlayer(new PlayerObservation(PlayerEventType.LEAVE, plugin.getServer().getPlayerCount()));
        sendDisconnect(binding);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        PlayerSessionRegistry.Binding<Player> binding = plugin.getPlayerSessions()
                .get(player.getUniqueId(), player);
        if (binding == null || !plugin.getPlayerSessions().isCurrent(binding)) {
            return;
        }
        PlayerServerConnectedMessage msg = new PlayerServerConnectedMessage()
                .setName(player.getUsername())
                .setUniqueId(player.getUniqueId())
                .setSessionId(binding.getSessionId())
                .setServerName(event.getServer().getServerInfo().getName());

        try {
            plugin.getBrokerClient().oneway(msg);
        } catch (RemotingException | InterruptedException e) {
            plugin.getLogger().error(e.getMessage(), e);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!PlayerSessionHandshake.CHANNEL.equals(event.getIdentifier().getId())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!PlayerSessionHandshake.isRequest(event.getData())
                || !(event.getSource() instanceof ServerConnection)) {
            return;
        }
        ServerConnection source = (ServerConnection) event.getSource();
        Player player = source.getPlayer();
        PlayerSessionRegistry.Binding<Player> binding = plugin.getPlayerSessions()
                .get(player.getUniqueId(), player);
        if (binding != null && plugin.getPlayerSessions().isCurrent(binding)) {
            source.sendPluginMessage(plugin.getSessionChannel(), PlayerSessionHandshake.response(binding.getSessionId()));
        }
    }

    private void disconnect(Player player, String message) {
        player.disconnect(Component.text(message).color(NamedTextColor.RED));
    }
}
