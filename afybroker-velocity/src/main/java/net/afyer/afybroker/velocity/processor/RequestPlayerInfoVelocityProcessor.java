package net.afyer.afybroker.velocity.processor;

import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.SyncUserProcessor;
import net.afyer.afybroker.core.message.RequestPlayerInfoMessage;
import net.afyer.afybroker.velocity.AfyBroker;

public class RequestPlayerInfoVelocityProcessor extends SyncUserProcessor<RequestPlayerInfoMessage> {

    private final AfyBroker plugin;

    public RequestPlayerInfoVelocityProcessor(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @Override
    public Object handleRequest(BizContext bizCtx, RequestPlayerInfoMessage request) throws Exception {
        return plugin.getPlayerSessions().getActiveSessionInfos();
    }

    @Override
    public String interest() {
        return RequestPlayerInfoMessage.class.getName();
    }
}
