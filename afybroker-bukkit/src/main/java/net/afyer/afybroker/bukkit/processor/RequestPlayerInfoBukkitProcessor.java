package net.afyer.afybroker.bukkit.processor;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.SyncUserProcessor;
import net.afyer.afybroker.bukkit.AfyBroker;
import net.afyer.afybroker.core.message.RequestPlayerInfoMessage;

public class RequestPlayerInfoBukkitProcessor extends SyncUserProcessor<RequestPlayerInfoMessage> {

    private final AfyBroker plugin;

    public RequestPlayerInfoBukkitProcessor(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @Override
    public Object handleRequest(BizContext bizCtx, RequestPlayerInfoMessage request) {
        return plugin.getPlayerSessions().getActiveSessionInfos();
    }

    @Override
    public String interest() {
        return RequestPlayerInfoMessage.class.getName();
    }
}
