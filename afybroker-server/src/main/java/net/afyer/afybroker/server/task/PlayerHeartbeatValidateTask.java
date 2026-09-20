package net.afyer.afybroker.server.task;

import com.alipay.remoting.exception.RemotingException;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.message.PlayerHeartbeatValidateMessage;
import net.afyer.afybroker.core.util.AbstractInvokeCallback;
import net.afyer.afybroker.server.BrokerServer;
import net.afyer.afybroker.server.processor.PlayerProxyDisconnectBrokerProcessor;
import net.afyer.afybroker.server.proxy.BrokerClientItem;
import net.afyer.afybroker.server.proxy.BrokerPlayer;
import net.afyer.afybroker.server.proxy.BrokerPlayerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家心跳验证任务
 *
 * @author Nipuru
 * @since 2023/11/25 12:37
 */
public class PlayerHeartbeatValidateTask extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerHeartbeatValidateTask.class);

    private static final long period = TimeUnit.SECONDS.toMillis(5L);

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BrokerServer brokerServer;

    public PlayerHeartbeatValidateTask(BrokerServer brokerServer) {
        this.brokerServer = brokerServer;
    }

    public void cancel() {
        this.running.set(false);
    }

    @SuppressWarnings("BusyWait")
    @Override
    public void run() {
        while (running.get()) {
            try {
                validate();
            } catch (Throwable t) {
                LOGGER.error("PlayerHeartbeatValidateTask encountered an exception", t);
            }

            if (period <= 0) {
                break;
            }

            try {
                Thread.sleep(period);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void validate() {
        BrokerPlayerManager playerManager = brokerServer.getPlayerManager();
        Collection<BrokerPlayer> brokerPlayers = playerManager.getPlayers();
        if (brokerPlayers.isEmpty()) return;
        Map<BrokerClientItem, List<PlayerSessionInfo>> map = new IdentityHashMap<>();
        for (BrokerPlayer player : brokerPlayers) {
            BrokerClientItem bungeeProxy = player.getProxy();
            map.computeIfAbsent(bungeeProxy, k -> new ArrayList<>())
                    .add(new PlayerSessionInfo()
                            .setUniqueId(player.getUniqueId())
                            .setSessionId(player.getSessionId())
                            .setName(player.getName()));
        }
        for (Map.Entry<BrokerClientItem, List<PlayerSessionInfo>> entry : map.entrySet()) {
            PlayerHeartbeatValidateMessage message = new PlayerHeartbeatValidateMessage()
                    .setPlayers(entry.getValue());
            BrokerClientItem bungeeProxy = entry.getKey();
            try {
                bungeeProxy.invokeWithCallback(message, new AbstractInvokeCallback() {
                    @Override
                    public void onResponse(Object result) {
                        List<PlayerSessionInfo> response = cast(result);
                        if (response.isEmpty()) return;
                        for (PlayerSessionInfo session : response) {
                            BrokerPlayer current = brokerServer.getPlayerManager().getPlayer(session.getUniqueId());
                            if (current != null && current.getProxy() == bungeeProxy
                                    && current.getSessionId().equals(session.getSessionId())) {
                                PlayerProxyDisconnectBrokerProcessor.handlePlayerRemove(brokerServer, current);
                            }
                        }
                    }

                    @Override
                    public void onException(Throwable e) {
                        LOGGER.error("Request player heart beat to bungee brokerClient:{} failed", bungeeProxy.getName());
                        LOGGER.error(e.getMessage(), e);
                    }
                });
            } catch (RemotingException | InterruptedException ignored) {
            }
        }
    }
}
