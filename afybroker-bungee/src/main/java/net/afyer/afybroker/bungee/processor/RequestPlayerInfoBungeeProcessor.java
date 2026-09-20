package net.afyer.afybroker.bungee.processor;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.SyncUserProcessor;
import net.afyer.afybroker.bungee.AfyBroker;
import net.afyer.afybroker.core.message.PlayerSessionInfo;
import net.afyer.afybroker.core.message.RequestPlayerInfoMessage;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.ArrayList;
import java.util.List;

public class RequestPlayerInfoBungeeProcessor extends SyncUserProcessor<RequestPlayerInfoMessage> {

    private final AfyBroker plugin;

    public RequestPlayerInfoBungeeProcessor(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @Override
    public Object handleRequest(BizContext bizCtx, RequestPlayerInfoMessage request) {
        List<PlayerSessionInfo> list = new ArrayList<>();
        for (PlayerSessionRegistry.Binding<ProxiedPlayer> binding : plugin.getPlayerSessions().getActiveSessions()) {
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
