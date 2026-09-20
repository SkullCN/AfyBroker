package net.afyer.afybroker.server.proxy;

import net.afyer.afybroker.core.Attributable;
import net.afyer.afybroker.core.AttributeContainer;
import net.afyer.afybroker.core.message.ConnectToServerMessage;
import net.afyer.afybroker.core.message.KickPlayerMessage;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家代理
 *
 * @author Nipuru
 * @since 2022/7/30 16:47
 */
public class BrokerPlayer implements Attributable {

    /**
     * 玩家UUID
     */
    private final UUID uniqueId;

    /** Proxy login connection identity */
    private final UUID sessionId;
    /**
     * 玩家名字
     */
    private final String name;
    /**
     * 玩家所在的 Proxy 服务器客户端代理
     */
    private final BrokerClientItem proxy;
    /**
     * 玩家属性
     */
    private final AttributeContainer attributes = new AttributeContainer();

    /**
     * 玩家所在的 Minecraft 服务器客户端代理
     */
    @Nullable
    private BrokerClientItem server;

    public BrokerPlayer(UUID uniqueId, String name, UUID sessionId, BrokerClientItem proxy) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.proxy = proxy;
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public String getName() {
        return name;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public BrokerClientItem getProxy() {
        return proxy;
    }

    @Nullable
    public BrokerClientItem getServer() {
        return server;
    }

    public void setServer(@Nullable BrokerClientItem server) {
        this.server = server;
    }

    public void kick(String message) throws Exception {
        KickPlayerMessage request = new KickPlayerMessage()
                .setUniqueId(uniqueId)
                .setSessionId(sessionId)
                .setMessage(message);

        proxy.oneway(request);
    }

    public void connectToServer(String serverName) throws Exception {
        ConnectToServerMessage request = new ConnectToServerMessage()
                .setUniqueId(uniqueId)
                .setSessionId(sessionId)
                .setServerName(serverName);

        proxy.oneway(request);
    }

    @Override
    public AttributeContainer getAttributeContainer() {
        return attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BrokerPlayer that = (BrokerPlayer) o;
        return uniqueId.equals(that.uniqueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueId);
    }

    @Override
    public String toString() {
        return "BrokerPlayer{" +
                ", uid=" + uniqueId +
                ", sessionId=" + sessionId +
                ", name='" + name + '\'' +
                ", bungeeProxy='" + proxy + '\'' +
                ", bukkitServer='" + proxy + '\'' +
                '}';
    }
}
