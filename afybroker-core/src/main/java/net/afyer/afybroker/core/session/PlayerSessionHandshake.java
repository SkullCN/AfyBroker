package net.afyer.afybroker.core.session;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Reserved plugin-message handshake between a proxy and its backend plugin. */
public final class PlayerSessionHandshake {

    public static final String CHANNEL = "afybroker:session";
    private static final byte REQUEST = 1;
    private static final byte RESPONSE = 2;

    private PlayerSessionHandshake() {
    }

    public static byte[] request() {
        return new byte[]{REQUEST};
    }

    public static boolean isRequest(byte[] data) {
        return data != null && data.length == 1 && data[0] == REQUEST;
    }

    public static byte[] response(UUID sessionId) {
        return ByteBuffer.allocate(1 + 16)
                .put(RESPONSE)
                .putLong(sessionId.getMostSignificantBits())
                .putLong(sessionId.getLeastSignificantBits())
                .array();
    }

    public static UUID readResponse(byte[] data) {
        if (data == null || data.length != 17 || data[0] != RESPONSE) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.get();
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
