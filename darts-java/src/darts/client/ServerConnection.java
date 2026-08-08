package darts.client;

import darts.common.Message;
import darts.common.Protocol;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Handles underlying TCP socket connection, stream length-prefix framing,
 * background network reading, heartbeat ping/pong, and reconnect-on-drop.
 */
public class ServerConnection {
    private static final int HEARTBEAT_INTERVAL_MS = 30_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1_000L;

    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Consumer<Message> messageHandler;
    private Consumer<String> errorHandler;
    private IntConsumer reconnectAttemptHandler;
    private Runnable reconnectSuccessHandler;
    private Runnable reconnectFailedHandler;

    private volatile boolean connected = false;
    private volatile boolean intentionalDisconnect = false;
    private volatile boolean reconnecting = false;
    private Thread readerThread;
    private Thread heartbeatThread;

    private String preservedUsername;
    private String preservedPassword = "";
    private String preservedRoom = "general";

    public ServerConnection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void setMessageHandler(Consumer<Message> handler) {
        this.messageHandler = handler;
    }

    public void setErrorHandler(Consumer<String> handler) {
        this.errorHandler = handler;
    }

    public void setReconnectAttemptHandler(IntConsumer handler) {
        this.reconnectAttemptHandler = handler;
    }

    public void setReconnectSuccessHandler(Runnable handler) {
        this.reconnectSuccessHandler = handler;
    }

    public void setReconnectFailedHandler(Runnable handler) {
        this.reconnectFailedHandler = handler;
    }

    /**
     * Stores session credentials used to re-authenticate after reconnect.
     */
    public void setSessionState(String username, String password, String room) {
        this.preservedUsername = username;
        this.preservedPassword = password != null ? password : "";
        if (room != null && !room.isBlank()) {
            this.preservedRoom = room;
        }
    }

    public String getPreservedRoom() {
        return preservedRoom;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Establishes TCP connection to the DARTS server and starts background threads.
     */
    public void connect() throws IOException {
        intentionalDisconnect = false;
        openSocket();
    }

    private void openSocket() throws IOException {
        closeSocketQuietly();
        socket = new Socket(host, port);
        socket.setSoTimeout(90_000); // Match server idle timeout for drop detection
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
        connected = true;

        readerThread = new Thread(this::readLoop, "DARTS-NetworkReader");
        readerThread.setDaemon(true);
        readerThread.start();
        startHeartbeat();
    }

    /**
     * Sends a length-prefixed Message to the server.
     */
    public synchronized void send(Message message) {
        if (!connected || socket == null || socket.isClosed()) {
            if (errorHandler != null) {
                errorHandler.accept("Cannot send message: not connected to server.");
            }
            return;
        }
        try {
            byte[] frame = Protocol.encodeFrame(message);
            out.write(frame);
            out.flush();
        } catch (IOException e) {
            handleUnexpectedDisconnect("Network write failed: " + e.getMessage());
        }
    }

    private void readLoop() {
        while (connected && !Thread.currentThread().isInterrupted()) {
            try {
                int length = in.readInt();
                if (length <= 0 || length > Protocol.MAX_PAYLOAD_SIZE) {
                    if (errorHandler != null) {
                        errorHandler.accept("Received invalid frame length: " + length);
                    }
                    handleUnexpectedDisconnect("Invalid frame length from server.");
                    break;
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                String json = new String(payload, StandardCharsets.UTF_8);
                Message msg = Message.fromJson(json);

                if (messageHandler != null) {
                    messageHandler.accept(msg);
                }
            } catch (java.net.SocketTimeoutException e) {
                // Timeout without data — send a ping to keep alive or detect dead connection
                if (connected && !intentionalDisconnect) {
                    String from = preservedUsername != null ? preservedUsername : "guest";
                    send(new Message(Protocol.MSG_PING, from, null, preservedRoom, ""));
                }
                // Don't break — continue the read loop
            } catch (IOException e) {
                if (connected && !intentionalDisconnect) {
                    handleUnexpectedDisconnect("Disconnected from server.");
                }
                break;
            } catch (Exception e) {
                if (errorHandler != null) {
                    errorHandler.accept("Error parsing server frame: " + e.getMessage());
                }
            }
        }
    }

    private void handleUnexpectedDisconnect(String reason) {
        if (!connected || intentionalDisconnect) {
            return;
        }
        stopHeartbeat();
        connected = false;
        closeSocketQuietly();
        if (errorHandler != null) {
            errorHandler.accept(reason);
        }
        if (!intentionalDisconnect && preservedUsername != null) {
            startReconnect();
        }
    }

    private void startReconnect() {
        if (reconnecting || intentionalDisconnect) {
            return;
        }
        reconnecting = true;
        Thread reconnectThread = new Thread(this::reconnectLoop, "DARTS-Reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    private void reconnectLoop() {
        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            if (intentionalDisconnect) {
                reconnecting = false;
                return;
            }
            if (reconnectAttemptHandler != null) {
                reconnectAttemptHandler.accept(attempt);
            }
            long backoffMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnecting = false;
                return;
            }
            if (intentionalDisconnect) {
                reconnecting = false;
                return;
            }
            try {
                openSocket();
                restoreSession();
                reconnecting = false;
                if (reconnectSuccessHandler != null) {
                    reconnectSuccessHandler.run();
                }
                return;
            } catch (IOException e) {
                if (errorHandler != null) {
                    errorHandler.accept("Reconnect attempt " + attempt + " failed: " + e.getMessage());
                }
            }
        }
        reconnecting = false;
        if (reconnectFailedHandler != null) {
            reconnectFailedHandler.run();
        }
    }

    private void restoreSession() {
        if (preservedUsername == null) {
            return;
        }
        send(new Message(Protocol.MSG_LOGIN, preservedUsername, null, preservedRoom, preservedPassword));
        if (preservedRoom != null && !preservedRoom.isBlank()) {
            send(new Message(Protocol.MSG_JOIN_ROOM, preservedUsername, null, preservedRoom, ""));
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatThread = new Thread(() -> {
            while (connected && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!connected || intentionalDisconnect) {
                    break;
                }
                String from = preservedUsername != null ? preservedUsername : "guest";
                send(new Message(Protocol.MSG_PING, from, null, preservedRoom, ""));
            }
        }, "DARTS-Heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void stopHeartbeat() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }

    /**
     * Closes the connection and stops background threads. Does not attempt reconnect.
     */
    public void disconnect() {
        intentionalDisconnect = true;
        reconnecting = false;
        connected = false;
        stopHeartbeat();
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        closeSocketQuietly();
    }

    private void closeSocketQuietly() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Socket already closed or unreachable — safe to ignore on shutdown.
        }
    }
}
