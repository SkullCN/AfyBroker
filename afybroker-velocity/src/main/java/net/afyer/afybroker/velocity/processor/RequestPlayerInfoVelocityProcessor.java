package net.afyer.afybroker.velocity.processor;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.SyncUserProcessor;
import com.velocitypowered.api.proxy.Player;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.message.RequestPlayerInfoMessage;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.afyer.afybroker.velocity.AfyBroker;

import java.util.ArrayList;
import java.util.List;

public class RequestPlayerInfoVelocityProcessor extends SyncUserProcessor<RequestPlayerInfoMessage> {

    private final AfyBroker plugin;

    public RequestPlayerInfoVelocityProcessor(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @Override
    public Object handleRequest(BizContext bizCtx, RequestPlayerInfoMessage request) throws Exception {
        List<PlayerSessionInfo> list = new ArrayList<>();
        for (PlayerSessionRegistry.Binding<Player> binding : plugin.getPlayerSessions().getActiveSessions()) {
            list.add(new PlayerSessionInfo()
                    .setUniqueId(binding.getUniqueId())
                    .setSessionId(binding.getSessionId())
                    .setName(binding.getName()));
        }
        return list;
    }

    @Override
    public String interest() {
        return RequestPlayerInfoMessage.class.getName();
    }
}
