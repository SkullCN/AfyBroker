package net.afyer.afybroker.server.processor;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.SyncUserProcessor;
import net.afyer.afybroker.core.BrokerClientType;
import net.afyer.afybroker.core.message.PlayerProxyConnectMessage;
import net.afyer.afybroker.core.message.PlayerProxyConnectResult;
import net.afyer.afybroker.core.observability.PlayerEventType;
import net.afyer.afybroker.core.observability.PlayerObservation;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.aware.BrokerServerAware;
import net.afyer.afybroker.server.event.PlayerProxyLoginEvent;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Nipuru
 * @since 2022/8/1 11:41
 */
public class PlayerProxyConnectBrokerProcessor extends SyncUserProcessor<PlayerProxyConnectMessage> implements BrokerServerAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerProxyConnectBrokerProcessor.class);

    private BrokerServer brokerServer;

    public void setBrokerServer(BrokerServer brokerServer) {
        this.brokerServer = brokerServer;
    }

    @Override
    public Object handleRequest(BizContext bizCtx, PlayerProxyConnectMessage request) {
        BrokerClientItem playerBungee = brokerServer.getClient(bizCtx);
        if (playerBungee == null || !Objects.equals(playerBungee.getType(), BrokerClientType.PROXY)) {
            return new PlayerProxyConnectResult().setSuccess(false);
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Received player bungee connect message => player[{}], bungeeClient[{}]",
                    request.getName(), playerBungee.getName());
        }

        if (request.getUniqueId() == null || request.getSessionId() == null) {
            return new PlayerProxyConnectResult().setSuccess(false);
        }

        BrokerPlayer brokerPlayer = new BrokerPlayer(request.getUniqueId(), request.getName(), request.getSessionId(), playerBungee);
        return handlePlayerLogin(brokerServer, brokerPlayer, request.getServerName());
    }

    public static SnapshotRegistration registerSnapshotPlayer(BrokerServer brokerServer, BrokerPlayer brokerPlayer) {
        BrokerPlayerManager playerManager = brokerServer.getPlayerManager();
        BrokerPlayer existing = playerManager.getPlayer(brokerPlayer.getUniqueId());
        if (existing == null) {
            existing = playerManager.addPlayer(brokerPlayer);
            if (existing == null) {
                return new SnapshotRegistration(brokerPlayer, true);
            }
        }
        if (!existing.getSessionId().equals(brokerPlayer.getSessionId())) {
            return null;
        }
        if (existing.getProxy() == brokerPlayer.getProxy()) {
            return new SnapshotRegistration(existing, false);
        }
        if (brokerServer.getClientManager().isCurrent(existing.getProxy())) {
            return null;
        }
        return playerManager.replaceSession(existing, brokerPlayer)
                ? new SnapshotRegistration(brokerPlayer, false) : null;
    }

    public static void publishSnapshotLogin(BrokerServer brokerServer, BrokerPlayer brokerPlayer) {
        notifyPlayerLogin(brokerServer, brokerServer.getPlayerManager(), brokerPlayer, null);
    }

    private static PlayerProxyConnectResult handlePlayerLogin(BrokerServer brokerServer, BrokerPlayer brokerPlayer, String serverName) {
        BrokerPlayerManager playerManager = brokerServer.getPlayerManager();
        AtomicBoolean added = new AtomicBoolean();
        boolean current = brokerServer.getClientManager().runIfCurrent(brokerPlayer.getProxy(),
                () -> added.set(playerManager.addPlayer(brokerPlayer) == null));
        boolean success = current && added.get();
        if (success) {
            serverName = notifyPlayerLogin(brokerServer, playerManager, brokerPlayer, serverName);
        }
        return new PlayerProxyConnectResult()
                .setSuccess(success)
                .setServerName(serverName);
    }

    public static final class SnapshotRegistration {
        private final BrokerPlayer player;
        private final boolean newLogin;

        private SnapshotRegistration(BrokerPlayer player, boolean newLogin) {
            this.player = player;
            this.newLogin = newLogin;
        }

        public BrokerPlayer getPlayer() {
            return player;
        }

        public boolean isNewLogin() {
            return newLogin;
        }
    }

    private static String notifyPlayerLogin(BrokerServer brokerServer, BrokerPlayerManager playerManager,
                                            BrokerPlayer brokerPlayer, String serverName) {
        brokerServer.getObservability().onPlayer(new PlayerObservation(PlayerEventType.JOIN, playerManager.size()));
        PlayerProxyLoginEvent event = new PlayerProxyLoginEvent(brokerPlayer, serverName);
        brokerServer.getPluginManager().callEvent(event);
        return event.getServerName();
    }

    @Override
    public String interest() {
        return PlayerProxyConnectMessage.class.getName();
    }

}
