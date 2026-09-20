package net.afyer.afybroker.core.session;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSessionRegistryTest {

    @Test
    void removingOldPlayerDoesNotRemoveReplacementSession() {
        PlayerSessionRegistry<Object> registry = new PlayerSessionRegistry<>();
        UUID uniqueId = UUID.randomUUID();
        Object oldPlayer = new Object();
        Object newPlayer = new Object();

        PlayerSessionRegistry.Binding<Object> old = registry.begin(uniqueId, "player", oldPlayer);
        assertTrue(registry.accept(old));
        PlayerSessionRegistry.Binding<Object> current = registry.begin(uniqueId, "player", newPlayer);
        assertTrue(registry.accept(current));

        assertNull(registry.get(uniqueId, old.getSessionId()));
        assertSame(old, registry.remove(uniqueId, oldPlayer));
        assertSame(current, registry.getCurrent(uniqueId));
        assertTrue(registry.isCurrent(current));
    }

    @Test
    void pendingPlayerMustBindProxySessionBeforeItCanBeAccepted() {
        PlayerSessionRegistry<Object> registry = new PlayerSessionRegistry<>();
        UUID uniqueId = UUID.randomUUID();
        Object player = new Object();
        PlayerSessionRegistry.Binding<Object> pending = registry.beginPending(uniqueId, "player", player);
        UUID sessionId = UUID.randomUUID();

        assertFalse(registry.accept(pending));
        assertTrue(registry.bind(pending, sessionId));
        assertTrue(registry.accept(pending));
        assertFalse(registry.bind(pending, UUID.randomUUID()));
        assertSame(pending, registry.get(uniqueId, sessionId));
    }

    @Test
    void sessionHandshakeOnlyAcceptsExactResponseFrames() {
        UUID sessionId = UUID.randomUUID();

        assertTrue(PlayerSessionHandshake.isRequest(PlayerSessionHandshake.request()));
        assertEquals(sessionId, PlayerSessionHandshake.readResponse(PlayerSessionHandshake.response(sessionId)));
        assertNull(PlayerSessionHandshake.readResponse(PlayerSessionHandshake.request()));
        assertNull(PlayerSessionHandshake.readResponse(new byte[17]));
    }
}
