package darts.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Protocol specification constants, framing rules, and message validation logic.
 * Encodes messages with a 4-byte big-endian length prefix followed by UTF-8 JSON payload.
 */
public final class Protocol {

    public static final int MAX_PAYLOAD_SIZE = 65536; // 64 KB
    public static final int MAX_USERNAME_LEN = 32;
    public static final int MAX_ROOM_LEN = 64;
    public static final int MAX_BODY_LEN = 2048;

    // Message type constants
    public static final String MSG_LOGIN = "MSG_LOGIN";
    public static final String MSG_REGISTER = "MSG_REGISTER";
    public static final String MSG_LOGIN_OK = "MSG_LOGIN_OK";
    public static final String MSG_LOGIN_FAIL = "MSG_LOGIN_FAIL";
    public static final String MSG_JOIN_ROOM = "MSG_JOIN_ROOM";
    public static final String MSG_LEAVE_ROOM = "MSG_LEAVE_ROOM";
    public static final String MSG_CREATE_ROOM = "MSG_CREATE_ROOM";
    public static final String MSG_ROOM_MSG = "MSG_ROOM_MSG";
    public static final String MSG_PRIVATE = "MSG_PRIVATE";
    public static final String MSG_USER_LIST = "MSG_USER_LIST";
    public static final String MSG_ROOM_LIST = "MSG_ROOM_LIST";
    public static final String MSG_HISTORY = "MSG_HISTORY";
    public static final String MSG_PRESENCE = "MSG_PRESENCE";
    public static final String MSG_ADMIN_KICK = "MSG_ADMIN_KICK";
    public static final String MSG_ADMIN_MUTE = "MSG_ADMIN_MUTE";
    public static final String MSG_ADMIN_UNMUTE = "MSG_ADMIN_UNMUTE";
    public static final String MSG_ERROR = "MSG_ERROR";
    public static final String MSG_PING = "MSG_PING";
    public static final String MSG_PONG = "MSG_PONG";
    public static final String MSG_QUIT = "MSG_QUIT";
    public static final String MSG_KEY_GET = "MSG_KEY_GET";
    public static final String MSG_KEY_PUT = "MSG_KEY_PUT";
    public static final String MSG_KEY_RESP = "MSG_KEY_RESP";

    private Protocol() {
        // Prevent instantiation
    }

    /**
     * Encodes a Message into wire format: 4-byte length header + UTF-8 JSON bytes.
     *
     * @param message the message to encode
     * @return byte array containing full length-prefixed frame
     * @throws IllegalArgumentException if payload exceeds MAX_PAYLOAD_SIZE
     */
    public static byte[] encodeFrame(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
        byte[] payload = message.toJson().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload size " + payload.length +
                    " exceeds maximum length " + MAX_PAYLOAD_SIZE);
        }
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * Encodes a Message into a ByteBuffer ready for writing to a SocketChannel.
     *
     * @param message the message to encode
     * @return flipped ByteBuffer ready for output
     */
    public static ByteBuffer encodeFrameBuffer(Message message) {
        byte[] data = encodeFrame(message);
        return ByteBuffer.wrap(data);
    }

    /**
     * Validates input lengths for incoming messages according to security specifications.
     *
     * @param msg the message to validate
     * @return null if valid, or a descriptive error string if invalid
     */
    public static String validateMessage(Message msg) {
        if (msg == null) {
            return "Message is null";
        }
        if (msg.getFrom() != null && msg.getFrom().length() > MAX_USERNAME_LEN) {
            return "Username 'from' exceeds maximum length of " + MAX_USERNAME_LEN;
        }
        if (msg.getTo() != null && msg.getTo().length() > MAX_USERNAME_LEN) {
            return "Username 'to' exceeds maximum length of " + MAX_USERNAME_LEN;
        }
        if (msg.getRoom() != null && msg.getRoom().length() > MAX_ROOM_LEN) {
            return "Room name exceeds maximum length of " + MAX_ROOM_LEN;
        }
        if (msg.getBody() != null && msg.getBody().length() > MAX_BODY_LEN) {
            return "Message body exceeds maximum length of " + MAX_BODY_LEN;
        }
        return null;
    }
}
