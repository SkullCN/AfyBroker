package net.afyer.afybroker.server.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.AsyncUserProcessor;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.PlayerProxyDisconnectMessage;
import net.afyer.afybroker.core.observability.PlayerEventType;
import net.afyer.afybroker.core.observability.PlayerObservation;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.aware.BrokerServerAware;
import net.afyer.afybroker.server.event.PlayerProxyLogoutEvent;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Nipuru
 * @since 2022/11/21 17:32
 */
public class PlayerProxyDisconnectBrokerProcessor extends AsyncUserProcessor<PlayerProxyDisconnectMessage> implements BrokerServerAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerProxyDisconnectBrokerProcessor.class);

    private BrokerServer brokerServer;

    public void setBrokerServer(BrokerServer brokerServer) {
        this.brokerServer = brokerServer;
    }

    @Override
    public void handleRequest(BizContext bizCtx, AsyncContext asyncCtx, PlayerProxyDisconnectMessage request) {
        BrokerClientItem playerBungee = brokerServer.getClient(bizCtx);
        if (playerBungee == null) {
            return;
        }
        if (!Objects.equals(playerBungee.getType(), BrokerClientType.PROXY)) {
            return;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Received player bungee disconnect message => player[{}], bungeeClient[{}]",
                    request.getName(), playerBungee.getName());
        }

        if (request.getUniqueId() == null || request.getSessionId() == null) {
            return;
        }
        BrokerPlayer player = brokerServer.getPlayerManager().getPlayer(request.getUniqueId());
        if (player == null || player.getProxy() != playerBungee
                || !player.getSessionId().equals(request.getSessionId())) {
            return;
        }
        handlePlayerRemove(brokerServer, player);
    }

    public static boolean handlePlayerRemove(BrokerServer brokerServer, BrokerPlayer expected) {
        BrokerPlayerManager playerManager = brokerServer.getPlayerManager();
        AtomicBoolean removed = new AtomicBoolean();
        boolean current = brokerServer.getClientManager().runIfCurrent(expected.getProxy(),
                () -> removed.set(playerManager.removePlayer(expected)));
        if (!current || !removed.get()) {
            return false;
        }
        publishPlayerLogout(brokerServer, expected);
        return true;
    }

    public static void publishPlayerLogout(BrokerServer brokerServer, BrokerPlayer expected) {
        BrokerPlayerManager playerManager = brokerServer.getPlayerManager();
        brokerServer.getPluginManager().callEvent(new PlayerProxyLogoutEvent(expected));
        brokerServer.getObservability().onPlayer(new PlayerObservation(PlayerEventType.LEAVE, playerManager.size()));
    }

    @Override
    public String interest() {
        return PlayerProxyDisconnectMessage.class.getName();
    }
}
