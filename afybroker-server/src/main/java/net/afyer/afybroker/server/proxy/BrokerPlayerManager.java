package net.afyer.afybroker.server.proxy;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家代理 管理器
 *
 * @author Nipuru
 * @since 2022/7/30 20:36
 */
public class BrokerPlayerManager {

    private final Map<UUID, BrokerPlayer> byUid = new ConcurrentHashMap<>();
    private final Map<String, BrokerPlayer> byName = new ConcurrentHashMap<>();
    private final Map<UUID, BrokerPlayer> view = Collections.unmodifiableMap(byUid);

    public synchronized Collection<BrokerPlayer> getPlayers() {
        return view.values();
    }

    public synchronized int size() {
        return byUid.size();
    }

    @Nullable
    public synchronized BrokerPlayer addPlayer(BrokerPlayer player) {
        UUID uid = player.getUniqueId();
        BrokerPlayer absent = byUid.putIfAbsent(uid, player);
        if (absent == null) {
            byName.put(player.getName(), player);
        }
        return absent;
    }

    public synchronized void removePlayer(UUID uid) {
        BrokerPlayer player = byUid.remove(uid);
        if (player != null) {
            if (byName.get(player.getName()) == player) {
                byName.remove(player.getName());
            }
        }
    }

    public synchronized boolean removePlayer(BrokerPlayer expected) {
        UUID uniqueId = expected.getUniqueId();
        if (byUid.get(uniqueId) != expected) {
            return false;
        }
        byUid.remove(uniqueId);
        if (byName.get(expected.getName()) == expected) {
            byName.remove(expected.getName());
        }
        return true;
    }

    public synchronized boolean replaceSession(BrokerPlayer expected, BrokerPlayer replacement) {
        UUID uniqueId = expected.getUniqueId();
        if (byUid.get(uniqueId) != expected
                || !expected.getSessionId().equals(replacement.getSessionId())) {
            return false;
        }
        replacement.setServer(expected.getServer());
        byUid.put(uniqueId, replacement);
        if (byName.get(expected.getName()) == expected) {
            byName.remove(expected.getName());
        }
        byName.put(replacement.getName(), replacement);
        return true;
    }

    public synchronized boolean isCurrent(BrokerPlayer expected) {
        return byUid.get(expected.getUniqueId()) == expected;
    }

    @Nullable
    public synchronized BrokerPlayer getPlayer(UUID uid) {
        return byUid.get(uid);
    }

    @Nullable
    public synchronized BrokerPlayer getPlayer(String name) {
        return byName.get(name);
    }
}
