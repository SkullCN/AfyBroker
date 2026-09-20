package net.afyer.afybroker.server.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.AsyncUserProcessor;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.PlayerServerJoinMessage;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.aware.BrokerServerAware;
import net.afyer.afybroker.server.event.PlayerServerJoinEvent;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * @author Nipuru
 * @since 2023/09/29 12:16
 */
public class PlayerServerJoinBrokerProcessor extends AsyncUserProcessor<PlayerServerJoinMessage> implements BrokerServerAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerServerJoinBrokerProcessor.class);

    private BrokerServer brokerServer;

    public void setBrokerServer(BrokerServer brokerServer) {
        this.brokerServer = brokerServer;
    }

    @Override
    public void handleRequest(BizContext bizCtx, AsyncContext asyncCtx, PlayerServerJoinMessage request) {
        BrokerClientItem currentBukkit = brokerServer.getClient(bizCtx);
        if (currentBukkit == null) return;
        if (!Objects.equals(currentBukkit.getType(), BrokerClientType.SERVER)) return;

        BrokerPlayer player = brokerServer.getPlayer(request.getUniqueId());
        if (player == null || request.getSessionId() == null
                || !player.getSessionId().equals(request.getSessionId())) return;

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Received player bukkit join message => player[{}], bukkitClient[{}]",
                    request.getName(), currentBukkit.getName());
        }

        handleBukkitJoin(brokerServer, player, currentBukkit);
    }

    public static void handleBukkitJoin(BrokerServer server, BrokerPlayer player, BrokerClientItem bukkitClient) {
        PlayerServerJoinEvent event = updateBukkitJoin(server, player, bukkitClient);
        if (event != null) {
            server.getPluginManager().callEvent(event);
        }
    }

    public static PlayerServerJoinEvent updateBukkitJoin(BrokerServer server, BrokerPlayer player,
                                                          BrokerClientItem bukkitClient) {
        if (!Objects.equals(bukkitClient.getType(), BrokerClientType.SERVER)) return null;
        BrokerClientItem[] previous = new BrokerClientItem[1];
        boolean[] changed = new boolean[1];
        boolean current = server.getPlayerManager().runIfCurrent(player, () -> {
            if (player.getServer() != bukkitClient) {
                previous[0] = player.getServer();
                player.setServer(bukkitClient);
                changed[0] = true;
            }
        });
        return current && changed[0] ? new PlayerServerJoinEvent(player, previous[0], bukkitClient) : null;
    }

    @Override
    public String interest() {
        return PlayerServerJoinMessage.class.getName();
    }

}
