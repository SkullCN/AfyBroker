package net.afyer.afybroker.core.message;

import java.io.Serializable;
import java.util.List;

/**
 * 玩家心跳验证
 *
 * @author Nipuru
 * @since 2023/11/25 12:31
 */
public class PlayerHeartbeatValidateMessage implements Serializable {
    private static final long serialVersionUID = -3464928414600404140L;

    /**
     * 需要验证的玩家集合
     */
    private List<PlayerSessionInfo> players;

    public List<PlayerSessionInfo> getPlayers() {
        return players;
    }

    public PlayerHeartbeatValidateMessage setPlayers(List<PlayerSessionInfo> players) {
        this.players = players;
        return this;
    }

    @Override
    public String toString() {
        return "PlayerHeartbeatValidateMessage{" +
                "players=" + players +
                '}';
    }
}
