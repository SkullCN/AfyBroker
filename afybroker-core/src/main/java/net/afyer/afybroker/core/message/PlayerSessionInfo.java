package net.afyer.afybroker.core.message;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Player identity and login session used by registration and heartbeat snapshots. */
public class PlayerSessionInfo implements Serializable {
    private static final long serialVersionUID = 5169312103223644875L;

    private UUID uniqueId;
    private UUID sessionId;
    private String name;

    public UUID getUniqueId() {
        return uniqueId;
    }

    public PlayerSessionInfo setUniqueId(UUID uniqueId) {
        this.uniqueId = uniqueId;
        return this;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public PlayerSessionInfo setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public String getName() {
        return name;
    }

    public PlayerSessionInfo setName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PlayerSessionInfo)) return false;
        PlayerSessionInfo that = (PlayerSessionInfo) obj;
        return Objects.equals(uniqueId, that.uniqueId) && Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueId, sessionId);
    }

    @Override
    public String toString() {
        return "PlayerSessionInfo{" +
                "uniqueId=" + uniqueId +
                ", sessionId=" + sessionId +
                ", name='" + name + '\'' +
                '}';
    }
}
