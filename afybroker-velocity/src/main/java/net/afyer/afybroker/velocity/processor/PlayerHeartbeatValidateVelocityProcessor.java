package net.afyer.afybroker.velocity.processor;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.SyncUserProcessor;
import com.velocitypowered.api.proxy.Player;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.message.PlayerHeartbeatValidateMessage;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.afyer.afybroker.velocity.AfyBroker;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Nipuru
 * @since 2023/11/25 12:32
 */
public class PlayerHeartbeatValidateVelocityProcessor extends SyncUserProcessor<PlayerHeartbeatValidateMessage> {

    private final AfyBroker plugin;

    public PlayerHeartbeatValidateVelocityProcessor(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @Override
    public Object handleRequest(BizContext bizCtx, PlayerHeartbeatValidateMessage request) {
        // 包含验证失败（已离线）的玩家
        List<PlayerSessionInfo> response = new ArrayList<>();
        for (PlayerSessionInfo player : request.getPlayers()) {
            if (player == null || player.getUniqueId() == null || player.getSessionId() == null) {
                continue;
            }
            PlayerSessionRegistry.Binding<Player> binding =
                    plugin.getPlayerSessions().get(player.getUniqueId(), player.getSessionId());
            if (binding == null || !plugin.getPlayerSessions().isCurrent(binding)) {
                response.add(player);
            }
        }
        return response;
    }

    @Override
    public String interest() {
        return PlayerHeartbeatValidateMessage.class.getName();
    }
}
