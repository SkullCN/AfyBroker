package net.afyer.afybroker.core.session;

import net.afyer.afybroker.core.message.PlayerSessionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Tracks a login session against the concrete platform player object. */
public final class PlayerSessionRegistry<P> {

    private final Map<UUID, Binding<P>> activePlayers = new java.util.HashMap<>();
    private final Map<UUID, Binding<P>> pendingPlayers = new java.util.HashMap<>();
    private final Map<P, Binding<P>> connections = new IdentityHashMap<>();

    public synchronized Binding<P> begin(UUID uniqueId, P player) {
        return begin(uniqueId, null, player);
    }

    public synchronized Binding<P> begin(UUID uniqueId, String name, P player) {
        return begin(uniqueId, UUID.randomUUID(), name, player);
    }

    public synchronized Binding<P> beginPending(UUID uniqueId, String name, P player) {
        Binding<P> existing = connections.get(player);
        if (existing != null && uniqueId.equals(existing.uniqueId) && existing.sessionId == null) {
            return existing;
        }
        return begin(uniqueId, null, name, player);
    }

    private Binding<P> begin(UUID uniqueId, UUID sessionId, String name, P player) {
        Objects.requireNonNull(uniqueId, "uniqueId");
        Objects.requireNonNull(player, "player");
        Binding<P> existing = connections.get(player);
        if (existing != null && uniqueId.equals(existing.uniqueId)) {
            return existing;
        }
        Binding<P> binding = new Binding<>(uniqueId, sessionId, name, player);
        connections.put(player, binding);
        Binding<P> previousPending = pendingPlayers.put(uniqueId, binding);
        if (previousPending != null && previousPending != activePlayers.get(uniqueId)) {
            connections.remove(previousPending.player, previousPending);
            previousPending.registered = false;
        }
        return binding;
    }

    public synchronized boolean bind(Binding<P> binding, UUID sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (binding == null || connections.get(binding.player) != binding
                || (binding.registered
                ? activePlayers.get(binding.uniqueId) != binding
                : pendingPlayers.get(binding.uniqueId) != binding)) {
            return false;
        }
        if (binding.sessionId != null) {
            return binding.sessionId.equals(sessionId);
        }
        binding.sessionId = sessionId;
        return true;
    }

    public synchronized boolean accept(Binding<P> binding) {
        if (binding == null || binding.sessionId == null || connections.get(binding.player) != binding
                || pendingPlayers.get(binding.uniqueId) != binding) {
            return false;
        }
        Binding<P> current = activePlayers.get(binding.uniqueId);
        if (current != null && current != binding) {
            current.registered = false;
        }
        binding.registered = true;
        activePlayers.put(binding.uniqueId, binding);
        pendingPlayers.remove(binding.uniqueId, binding);
        return true;
    }

    public synchronized Binding<P> get(UUID uniqueId, UUID sessionId) {
        Binding<P> binding = activePlayers.get(uniqueId);
        return binding != null && binding.registered && binding.sessionId != null && binding.sessionId.equals(sessionId)
                ? binding : null;
    }

    public synchronized Binding<P> get(UUID uniqueId, P player) {
        Binding<P> binding = activePlayers.get(uniqueId);
        return binding != null && binding.registered && binding.player == player ? binding : null;
    }

    /** Returns pending or accepted state for this concrete player connection. */
    public synchronized Binding<P> getConnection(P player) {
        return connections.get(player);
    }

    public synchronized Binding<P> getCurrent(UUID uniqueId) {
        Binding<P> binding = activePlayers.get(uniqueId);
        return binding != null && binding.registered ? binding : null;
    }

    public synchronized Binding<P> remove(UUID uniqueId, P player) {
        Binding<P> binding = connections.get(player);
        if (binding == null || !binding.uniqueId.equals(uniqueId)) {
            return null;
        }
        connections.remove(player);
        if (pendingPlayers.get(uniqueId) == binding) {
            pendingPlayers.remove(uniqueId);
        }
        if (activePlayers.get(uniqueId) == binding) {
            activePlayers.remove(uniqueId);
        }
        binding.registered = false;
        return binding;
    }

    public synchronized boolean isCurrent(Binding<P> binding) {
        return binding != null
                && binding.registered
                && connections.get(binding.player) == binding
                && activePlayers.get(binding.uniqueId) == binding;
    }

    public synchronized List<Binding<P>> getActiveSessions() {
        return Collections.unmodifiableList(new ArrayList<>(activePlayers.values()));
    }

    public synchronized List<PlayerSessionInfo> getActiveSessionInfos() {
        List<PlayerSessionInfo> sessions = new ArrayList<>(activePlayers.size());
        for (Binding<P> binding : activePlayers.values()) {
            sessions.add(new PlayerSessionInfo()
                    .setUniqueId(binding.getUniqueId())
                    .setSessionId(binding.getSessionId())
                    .setName(binding.getName()));
        }
        return Collections.unmodifiableList(sessions);
    }

    public static final class Binding<P> {
        private final UUID uniqueId;
        private volatile UUID sessionId;
        private final P player;
        private volatile boolean registered;
        private final String name;

        private Binding(UUID uniqueId, UUID sessionId, String name, P player) {
            this.uniqueId = uniqueId;
            this.sessionId = sessionId;
            this.name = name;
            this.player = player;
        }

        public UUID getUniqueId() {
            return uniqueId;
        }

        public UUID getSessionId() {
            return sessionId;
        }

        public String getName() {
            return name;
        }

        public P getPlayer() {
            return player;
        }

        public boolean isRegistered() {
            return registered;
        }
    }
}
