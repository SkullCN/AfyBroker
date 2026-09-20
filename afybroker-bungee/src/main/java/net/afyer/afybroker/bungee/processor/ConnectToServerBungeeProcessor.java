package net.afyer.afybroker.bungee.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.AsyncUserProcessor;
import net.afyer.afybroker.bungee.AfyBroker;
import net.afyer.afybroker.core.message.ConnectToServerMessage;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Objects;

/**
 * @author Nipuru
 * @since 2022/9/6 17:35
 */
public class ConnectToServerBungeeProcessor extends AsyncUserProcessor<ConnectToServerMessage> {

    private final AfyBroker plugin;
    private static Field PENDING_CONNECT_FIELD;

    public ConnectToServerBungeeProcessor(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public void handleRequest(BizContext bizCtx, AsyncContext asyncCtx, ConnectToServerMessage message) throws Exception {
        ProxyServer bungee = plugin.getProxy();

        ServerInfo target = bungee.getServerInfo(message.getServerName());
        if (target == null) return;

        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = plugin.getPlayerSessions()
                .get(message.getUniqueId(), message.getSessionId());
        if (binding == null || !plugin.getPlayerSessions().isCurrent(binding)) return;
        ProxiedPlayer player = binding.getPlayer();

        synchronized (player) {
            if (player.getServer() != null && Objects.equals(player.getServer().getInfo(), target)) return;

            if (PENDING_CONNECT_FIELD == null) {
                Field field = player.getClass().getDeclaredField("pendingConnects");
                field.setAccessible(true);
                PENDING_CONNECT_FIELD = field;
            }
            if (((Collection<ServerInfo>) PENDING_CONNECT_FIELD.get(player)).contains(target)) {
                return;
            }
            player.connect(target);
        }
    }

    @Override
    public String interest() {
        return ConnectToServerMessage.class.getName();
    }
}
