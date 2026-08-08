package darts.server;

import darts.common.Message;
import darts.common.Protocol;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Manages per-connected-client state, wire protocol framing, non-blocking socket I/O,
 * and outgoing write queue for a single channel in the Selector event loop.
 */
public class ClientSession {
    private final SocketChannel channel;
    private final SelectionKey key;
    private final Server server;

    private Database.UserRecord userRecord;
    private String username;
    private String currentRoom = "general";
    private long lastActivityTime = System.currentTimeMillis();

    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    private final ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
    private ByteBuffer payloadBuffer = null;
    private boolean readingHeader = true;
    private int payloadLength = 0;

    private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
    private boolean closed = false;

    public ClientSession(SocketChannel channel, SelectionKey key, Server server) {
        this.channel = channel;
        this.key = key;
        this.server = server;
    }

    public Database.UserRecord getUserRecord() {
        return userRecord;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public void setCurrentRoom(String currentRoom) {
        this.currentRoom = currentRoom;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public void updateActivityTime() {
        this.lastActivityTime = System.currentTimeMillis();
    }

    /**
     * Disconnects the client if inactive for more than 90 seconds.
     */
    public void checkIdleTimeout(long now) {
        if (!closed && (now - lastActivityTime > 90000)) {
            System.out.println("Disconnecting idle client: " + (username != null ? username : getClientAddress()));
            sendError("Connection idle timeout (90 seconds)");
            close();
        }
    }

    /**
     * Reads pending bytes from the socket channel and processes accumulated length-prefixed frames.
     */
    public void handleRead() {
        if (closed) return;
        updateActivityTime();
        try {
            int bytesRead = channel.read(readBuffer);
            if (bytesRead == -1) {
                close();
                return;
            }
            readBuffer.flip();
            while (readBuffer.hasRemaining()) {
                if (readingHeader) {
                    while (readBuffer.hasRemaining() && lengthBuffer.hasRemaining()) {
                        lengthBuffer.put(readBuffer.get());
                    }
                    if (!lengthBuffer.hasRemaining()) {
                        lengthBuffer.flip();
                        payloadLength = lengthBuffer.getInt();
                        lengthBuffer.clear();

                        if (payloadLength <= 0 || payloadLength > Protocol.MAX_PAYLOAD_SIZE) {
                            sendError("Frame length " + payloadLength + " exceeds maximum bound of " + Protocol.MAX_PAYLOAD_SIZE);
                            close();
                            return;
                        }
                        payloadBuffer = ByteBuffer.allocate(payloadLength);
                        readingHeader = false;
                    }
                }
                if (!readingHeader) {
                    while (readBuffer.hasRemaining() && payloadBuffer.hasRemaining()) {
                        payloadBuffer.put(readBuffer.get());
                    }
                    if (!payloadBuffer.hasRemaining()) {
                        payloadBuffer.flip();
                        byte[] payloadBytes = new byte[payloadLength];
                        payloadBuffer.get(payloadBytes);
                        String json = new String(payloadBytes, StandardCharsets.UTF_8);

                        readingHeader = true;
                        payloadBuffer = null;

                        processFrame(json);
                    }
                }
            }
            readBuffer.clear();
        } catch (IOException e) {
            close();
        } catch (Exception e) {
            System.err.println("Error processing client input for " + getClientAddress() + ": " + e.getMessage());
            sendError("Internal error processing frame: " + e.getMessage());
        }
    }

    /**
     * Non-blocking write handler that drains the outgoing buffer queue to the socket.
     */
    public void handleWrite() {
        if (closed) return;
        updateActivityTime();
        try {
            while (!writeQueue.isEmpty()) {
                ByteBuffer buf = writeQueue.peek();
                channel.write(buf);
                if (buf.hasRemaining()) {
                    // Socket send buffer full; remain registered for OP_WRITE
                    return;
                }
                writeQueue.poll();
            }
            // All pending writes completed; unregister OP_WRITE interest
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        } catch (IOException e) {
            close();
        }
    }

    /**
     * Enqueues a Message to be sent to this client in non-blocking mode.
     */
    public void send(Message message) {
        if (closed) return;
        try {
            ByteBuffer buf = Protocol.encodeFrameBuffer(message);
            writeQueue.add(buf);
            if (key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                key.selector().wakeup();
            }
        } catch (Exception e) {
            System.err.println("Error encoding message for client: " + e.getMessage());
        }
    }

    /**
     * Helper to send an error message to the client.
     */
    public void sendError(String errorMsg) {
        send(new Message(Protocol.MSG_ERROR, "server", username, currentRoom, errorMsg));
    }

    /**
     * Processes a fully-framed JSON message payload from the client.
     */
    private void processFrame(String json) {
        Message msg;
        try {
            msg = Message.fromJson(json);
        } catch (Exception e) {
            sendError("Malformed JSON message: " + e.getMessage());
            return;
        }

        String validationError = Protocol.validateMessage(msg);
        if (validationError != null) {
            sendError("Validation error: " + validationError);
            return;
        }

        String type = msg.getType();

        switch (type) {
            case Protocol.MSG_REGISTER -> handleRegister(msg);
            case Protocol.MSG_LOGIN -> handleLogin(msg);
            case Protocol.MSG_CREATE_ROOM -> handleCreateRoom(msg);
            case Protocol.MSG_JOIN_ROOM -> {
                String targetRoom = (msg.getRoom() != null && !msg.getRoom().isBlank()) ? msg.getRoom() : currentRoom;
                joinRoomAndSendHistory(targetRoom);
            }
            case Protocol.MSG_LEAVE_ROOM -> {
                joinRoomAndSendHistory("general");
            }
            case Protocol.MSG_ROOM_LIST -> handleRoomList();
            case Protocol.MSG_USER_LIST -> handleUserList();
            case Protocol.MSG_PRIVATE -> handlePrivateMessage(msg);
            case Protocol.MSG_ADMIN_KICK -> handleAdminKick(msg);
            case Protocol.MSG_ADMIN_MUTE -> handleAdminMute(msg);
            case Protocol.MSG_ADMIN_UNMUTE -> handleAdminUnmute(msg);
            case Protocol.MSG_KEY_PUT -> handleKeyPut(msg);
            case Protocol.MSG_KEY_GET -> handleKeyGet(msg);
            case Protocol.MSG_ROOM_MSG -> handleRoomMessage(msg);
            case Protocol.MSG_PING -> send(new Message(Protocol.MSG_PONG, "server", username, currentRoom, "pong"));
            case Protocol.MSG_QUIT -> close();
            default -> {
                // Fallback broadcast
                Room room = server.getRoom(currentRoom);
                Message broadcastMsg = new Message(type, username != null ? username : "guest", msg.getTo(), currentRoom, msg.getBody(), System.currentTimeMillis());
                room.broadcast(broadcastMsg);
            }
        }
    }

    private void handleRegister(Message msg) {
        String requestedUser = msg.getFrom();
        String password = msg.getBody();

        server.getWorkerPool().submit(() -> {
            AuthManager.RegisterResult result = server.getAuthManager().register(requestedUser, password);
            if (result.isSuccess()) {
                this.userRecord = result.user();
                this.username = result.user().username();
                // Disconnect any existing session with the same username
                ClientSession existing = server.getSessionByUsername(username);
                if (existing != null && existing != this) {
                    existing.sendError("Your session has been replaced by a new login");
                    existing.close();
                }
                send(new Message(Protocol.MSG_LOGIN_OK, "server", username, currentRoom, "Registration & login successful"));
                joinRoomAndSendHistory(currentRoom);
            } else {
                send(new Message(Protocol.MSG_LOGIN_FAIL, "server", null, currentRoom, result.reason()));
            }
        });
    }

    private void handleLogin(Message msg) {
        String requestedUser = msg.getFrom();
        String password = msg.getBody();

        if (password == null || password.isEmpty()) {
            this.username = (requestedUser != null && !requestedUser.isBlank()) ? requestedUser : "User_" + channel.socket().getPort();
            send(new Message(Protocol.MSG_LOGIN_OK, "server", username, currentRoom, "Logged in as guest"));
            joinRoomAndSendHistory(currentRoom);
            return;
        }

        server.getWorkerPool().submit(() -> {
            AuthManager.LoginResult result = server.getAuthManager().login(requestedUser, password);
            if (result.isSuccess()) {
                this.userRecord = result.user();
                this.username = result.user().username();
                // Disconnect any existing session with the same username
                ClientSession existing = server.getSessionByUsername(username);
                if (existing != null && existing != this) {
                    existing.sendError("Your session has been replaced by a new login");
                    existing.close();
                }
                send(new Message(Protocol.MSG_LOGIN_OK, "server", username, currentRoom, "Login successful"));
                joinRoomAndSendHistory(currentRoom);
            } else {
                send(new Message(Protocol.MSG_LOGIN_FAIL, "server", null, currentRoom, result.reason()));
            }
        });
    }

    private void handleCreateRoom(Message msg) {
        String newRoom = msg.getBody();
        if (newRoom == null || newRoom.isBlank()) {
            sendError("Room name required");
            return;
        }
        server.getWorkerPool().submit(() -> {
            try {
                Integer creatorId = (userRecord != null) ? userRecord.id() : null;
                server.getDatabase().createRoom(newRoom, creatorId);
                // Join immediately on Selector thread
                server.getWorkerPool().submit(() -> joinRoomAndSendHistory(newRoom));
            } catch (SQLException e) {
                sendError("Failed to create room: " + e.getMessage());
            }
        });
    }

    private void handleRoomList() {
        server.getWorkerPool().submit(() -> {
            try {
                List<String> list = server.getDatabase().getAllRooms();
                String body = String.join(", ", list);
                send(new Message(Protocol.MSG_ROOM_LIST, "server", username, currentRoom, body));
            } catch (SQLException e) {
                sendError("Failed to retrieve room list: " + e.getMessage());
            }
        });
    }

    private void handleUserList() {
        Room room = server.getRoom(currentRoom);
        List<String> list = new ArrayList<>();
        for (ClientSession session : room.getSessions()) {
            if (session.getUsername() != null) {
                list.add(session.getUsername());
            }
        }
        String body = String.join(", ", list);
        send(new Message(Protocol.MSG_USER_LIST, "server", username, currentRoom, body));
    }

    private void handlePrivateMessage(Message msg) {
        String recipient = msg.getTo();
        if (recipient == null || recipient.isBlank()) {
            sendError("Private message recipient 'to' required");
            return;
        }
        ClientSession target = server.getSessionByUsername(recipient);
        if (target == null) {
            sendError("User '" + recipient + "' is offline");
            return;
        }
        // Forward message to target
        target.send(new Message(Protocol.MSG_PRIVATE, username != null ? username : "guest", recipient, currentRoom, msg.getBody(), msg.getTimestamp()));
    }

    private void handleAdminKick(Message msg) {
        String targetUser = msg.getTo();
        if (userRecord == null || !userRecord.isAdmin()) {
            sendError("Permission denied: admin privileges required");
            return;
        }
        ClientSession target = server.getSessionByUsername(targetUser);
        if (target == null) {
            sendError("User '" + targetUser + "' not found");
            return;
        }
        System.out.println("Admin kicked client: " + targetUser);
        target.sendError("You have been kicked by an administrator");
        target.close();
    }

    private void handleAdminMute(Message msg) {
        String targetUser = msg.getTo();
        if (userRecord == null || !userRecord.isAdmin()) {
            sendError("Permission denied: admin privileges required");
            return;
        }
        if (targetUser == null || targetUser.isBlank()) {
            sendError("Target username required for mute");
            return;
        }
        Room room = server.getRoom(currentRoom);
        room.muteUser(targetUser);
        room.broadcast(new Message(Protocol.MSG_PRESENCE, "server", null, currentRoom,
                targetUser + " has been muted by " + username));
        System.out.println("Admin " + username + " muted " + targetUser + " in " + currentRoom);
    }

    private void handleAdminUnmute(Message msg) {
        String targetUser = msg.getTo();
        if (userRecord == null || !userRecord.isAdmin()) {
            sendError("Permission denied: admin privileges required");
            return;
        }
        if (targetUser == null || targetUser.isBlank()) {
            sendError("Target username required for unmute");
            return;
        }
        Room room = server.getRoom(currentRoom);
        room.unmuteUser(targetUser);
        room.broadcast(new Message(Protocol.MSG_PRESENCE, "server", null, currentRoom,
                targetUser + " has been unmuted by " + username));
        System.out.println("Admin " + username + " unmuted " + targetUser + " in " + currentRoom);
    }

    private void handleKeyPut(Message msg) {
        if (userRecord == null) {
            sendError("Must be logged in to set E2E DM key");
            return;
        }
        String pubKey = msg.getBody();
        server.getWorkerPool().submit(() -> {
            try {
                server.getDatabase().updatePublicKey(userRecord.id(), pubKey);
                this.userRecord = server.getDatabase().getUserByUsername(username);
            } catch (SQLException e) {
                sendError("Failed to save E2E DM key: " + e.getMessage());
            }
        });
    }

    private void handleKeyGet(Message msg) {
        String targetUser = msg.getBody();
        server.getWorkerPool().submit(() -> {
            try {
                String key = server.getDatabase().getPublicKey(targetUser);
                String responseBody = targetUser + "|" + (key != null ? key : "");
                send(new Message(Protocol.MSG_KEY_RESP, "server", msg.getFrom(), currentRoom, responseBody));
            } catch (SQLException e) {
                sendError("Failed to get E2E DM key: " + e.getMessage());
            }
        });
    }

    private void handleRoomMessage(Message msg) {
        String targetRoom = (msg.getRoom() != null && !msg.getRoom().isBlank()) ? msg.getRoom() : currentRoom;
        Room room = server.getRoom(targetRoom);
        String senderName = username != null ? username : (msg.getFrom() != null ? msg.getFrom() : "guest");

        // Block muted users from sending room messages
        if (room.isMuted(senderName)) {
            sendError("You are muted in room '" + targetRoom + "'");
            return;
        }

        Message broadcastMsg = new Message(Protocol.MSG_ROOM_MSG, senderName, null, targetRoom, msg.getBody(), System.currentTimeMillis());
        room.broadcast(broadcastMsg);

        if (userRecord != null) {
            final Database.UserRecord senderUser = userRecord;
            final String body = msg.getBody();
            server.getWorkerPool().submit(() -> {
                try {
                    Integer roomId = server.getDatabase().getRoomId(targetRoom);
                    if (roomId != null) {
                        server.getDatabase().saveMessage(roomId, senderUser.id(), null, body);
                    }
                } catch (Exception e) {
                    System.err.println("Error persisting room message: " + e.getMessage());
                }
            });
        }
    }

    public void joinRoomAndSendHistory(String targetRoom) {
        if (targetRoom == null || targetRoom.isBlank()) targetRoom = "general";
        
        // Remove from current room first
        if (currentRoom != null) {
            server.getRoom(currentRoom).leave(this);
            if (username != null) {
                server.getRoom(currentRoom).broadcast(new Message(Protocol.MSG_PRESENCE, "server", null, currentRoom, username + " left the room"));
            }
        }

        this.currentRoom = targetRoom;
        Room room = server.getRoom(targetRoom);
        room.join(this);

        final String roomToFetch = targetRoom;
        server.getWorkerPool().submit(() -> {
            try {
                List<Message> history = server.getDatabase().getRecentRoomMessages(roomToFetch, 50);
                if (!history.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Message m : history) {
                        sb.append(m.getTimestamp()).append("|").append(m.getFrom()).append("|").append(m.getBody()).append("\n");
                    }
                    send(new Message(Protocol.MSG_HISTORY, "server", username, roomToFetch, sb.toString()));
                }
            } catch (Exception e) {
                System.err.println("Error fetching room history: " + e.getMessage());
            }
        });

        if (username != null) {
            room.broadcast(new Message(Protocol.MSG_PRESENCE, "server", null, targetRoom, username + " joined the room"), this);
        }
    }

    /**
     * Closes the client session and removes it from server room membership.
     */
    public void close() {
        if (closed) return;
        closed = true;
        try {
            // Flush remaining write queue to the socket before closing channel
            while (!writeQueue.isEmpty()) {
                ByteBuffer buf = writeQueue.peek();
                try {
                    channel.write(buf);
                } catch (IOException e) {
                    break;
                }
                if (buf.hasRemaining()) {
                    break; // Channel buffer full
                }
                writeQueue.poll();
            }

            if (currentRoom != null && server != null) {
                Room room = server.getRoom(currentRoom);
                room.leave(this);
                if (username != null) {
                    room.broadcast(new Message(Protocol.MSG_PRESENCE, "server", null, currentRoom, username + " left the room"));
                }
            }
            key.cancel();
            channel.close();
            System.out.println("Closed connection for " + (username != null ? username : getClientAddress()));
        } catch (IOException ignored) {
        }
    }

    private String getClientAddress() {
        try {
            return channel.getRemoteAddress().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
