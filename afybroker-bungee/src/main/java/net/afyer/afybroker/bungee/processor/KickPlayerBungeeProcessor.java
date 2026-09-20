package net.afyer.afybroker.bungee.processor;

import com.alipay.remoting.AsyncContext;
import com.alipay.remoting.BizContext;
import com.alipay.remoting.rpc.protocol.AsyncUserProcessor;
import net.afyer.afybroker.bungee.AfyBroker;
import net.afyer.afybroker.core.message.KickPlayerMessage;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.afyer.afybroker.core.session.PlayerSessionRegistry;

/**
 * @author Nipuru
 * @since 2022/10/10 10:34
 */
public class KickPlayerBungeeProcessor extends AsyncUserProcessor<KickPlayerMessage> {

    private final AfyBroker plugin;

    public KickPlayerBungeeProcessor(AfyBroker plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handleRequest(BizContext bizCtx, AsyncContext asyncCtx, KickPlayerMessage request) {
        PlayerSessionRegistry.Binding<ProxiedPlayer> binding = plugin.getPlayerSessions()
                .get(request.getUniqueId(), request.getSessionId());
        if (binding == null || !plugin.getPlayerSessions().isCurrent(binding)) {
            return;
        }
        ProxiedPlayer player = binding.getPlayer();

        if (request.getMessage() != null) {
            BaseComponent[] baseComponents = TextComponent.fromLegacyText(request.getMessage());
            player.disconnect(baseComponents);
        } else {
            player.disconnect();
        }

    }

    @Override
    public String interest() {
        return KickPlayerMessage.class.getName();
    }
}
