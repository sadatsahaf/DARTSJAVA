package darts.client;

import darts.common.Message;
import darts.common.Protocol;
import darts.common.CryptoUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles terminal rendering with ANSI colors, command history, user input parsing,
 * and incoming message formatting for the client. Includes support for multi-room commands
 * and end-to-end encrypted direct messaging.
 */
public class ConsoleUI {
    private static final int MAX_HISTORY = 50;
    private static final String[] KNOWN_COMMANDS = {
            "/login", "/register", "/join", "/leave", "/create-room",
            "/pm", "/rooms", "/users", "/all", "/help", "/quit",
            "/exit", "/kick", "/mute", "/unmute"
    };
    private static final String PROMPT = "> ";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withLocale(Locale.US).withZone(ZoneId.systemDefault());

    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String BOLD = "\u001B[1m";
    private static final String[] USER_COLORS = {
            "\u001B[36m",
            "\u001B[32m",
            "\u001B[33m",
            "\u001B[35m",
            "\u001B[34m",
            "\u001B[91m",
            "\u001B[92m",
            "\u001B[93m"
    };

    private final ServerConnection connection;
    private final List<String> commandHistory = new ArrayList<>();
    private final Object printLock = new Object();

    private String username;
    private String password = "";
    private String currentRoom = "general";
    private volatile boolean running = true;

    // E2E Encrypted DMs (Phase 6)
    private PrivateKey privateKey;
    private PublicKey publicKey;
    private final Map<String, PublicKey> publicKeyCache = new ConcurrentHashMap<>();
    private final Map<String, String> pendingPms = new ConcurrentHashMap<>();

    public ConsoleUI(ServerConnection connection) {
        this.connection = connection;
        enableAnsiIfPossible();
    }

    public void setUsername(String username) {
        this.username = username;
        syncSessionState();
    }

    public String getUsername() {
        return username;
    }

    public void setCurrentRoom(String room) {
        if (room != null && !room.isBlank()) {
            this.currentRoom = room;
            syncSessionState();
        }
    }

    private void syncSessionState() {
        connection.setSessionState(username, password, currentRoom);
    }

    /**
     * Starts the interactive command line input loop with command history navigation.
     */
    public void startInputLoop() {
        printHelp();
        while (running) {
            String line;
            try {
                line = readLineWithHistory();
            } catch (IOException e) {
                printError("Input error: " + e.getMessage());
                break;
            }
            if (line == null) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("/")) {
                handleCommand(line);
            } else {
                sendRoomMessage(line);
            }
        }
    }

    private void sendRoomMessage(String text) {
        if (username == null) {
            printSystem("Please log in first with /login <username> [password].");
            return;
        }
        Message msg = new Message(Protocol.MSG_ROOM_MSG, username, null, currentRoom, text);
        connection.send(msg);
    }

    private void handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/login", "/name" -> handleLogin(arg);
            case "/register" -> handleRegister(arg);
            case "/join" -> handleJoin(arg);
            case "/leave" -> handleLeave();
            case "/create-room" -> handleCreateRoom(arg);
            case "/pm" -> handlePrivateMessageCommand(arg);
            case "/kick" -> handleKickCommand(arg);
            case "/mute" -> handleMuteCommand(arg);
            case "/unmute" -> handleUnmuteCommand(arg);
            case "/rooms" -> connection.send(new Message(
                    Protocol.MSG_ROOM_LIST, username, null, currentRoom, ""));
            case "/users" -> connection.send(new Message(
                    Protocol.MSG_USER_LIST, username, null, currentRoom, ""));
            case "/all" -> {
                if (arg.isEmpty()) {
                    printSystem("Usage: /all <message>");
                    return;
                }
                sendRoomMessage(arg);
            }
            case "/help" -> printHelp();
            case "/quit", "/exit" -> quit();
            default -> printSystem("Unknown command: " + cmd + ". Type /help for available commands.");
        }
    }

    private void handleLogin(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /login <username> [password]");
            return;
        }
        String[] tokens = arg.split("\\s+", 2);
        this.username = tokens[0];
        this.password = tokens.length > 1 ? tokens[1] : "";
        syncSessionState();
        connection.send(new Message(Protocol.MSG_LOGIN, username, null, currentRoom, password));
    }

    private void handleRegister(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /register <username> <password>");
            return;
        }
        String[] tokens = arg.split("\\s+", 2);
        if (tokens.length < 2 || tokens[1].isBlank()) {
            printSystem("Usage: /register <username> <password>");
            return;
        }
        this.username = tokens[0];
        this.password = tokens[1];
        syncSessionState();
        connection.send(new Message(Protocol.MSG_REGISTER, username, null, currentRoom, password));
    }

    private void handleJoin(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /join <room>");
            return;
        }
        if (username == null) {
            printSystem("Please log in first with /login <username> [password].");
            return;
        }
        currentRoom = arg;
        syncSessionState();
        connection.send(new Message(Protocol.MSG_JOIN_ROOM, username, null, currentRoom, ""));
        printSystem("Joining room '" + currentRoom + "'...");
    }

    private void handleLeave() {
        if (username == null) {
            printSystem("Please log in first with /login <username> [password].");
            return;
        }
        connection.send(new Message(Protocol.MSG_LEAVE_ROOM, username, null, currentRoom, ""));
        printSystem("Left room '" + currentRoom + "'.");
        currentRoom = "general";
        syncSessionState();
    }

    private void handleCreateRoom(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /create-room <room_name>");
            return;
        }
        if (username == null) {
            printSystem("Please log in first with /login <username> [password].");
            return;
        }
        connection.send(new Message(Protocol.MSG_CREATE_ROOM, username, null, currentRoom, arg));
        printSystem("Creating room '" + arg + "'...");
    }

    private void handlePrivateMessageCommand(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /pm <username> <message>");
            return;
        }
        String[] parts = arg.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            printSystem("Usage: /pm <username> <message>");
            return;
        }
        String target = parts[0];
        String rawMsg = parts[1];

        PublicKey cachedPub = publicKeyCache.get(target);
        if (cachedPub != null) {
            sendEncryptedPm(target, rawMsg, cachedPub);
        } else {
            pendingPms.put(target, rawMsg);
            connection.send(new Message(Protocol.MSG_KEY_GET, username, target, currentRoom, target));
        }
    }

    private void sendEncryptedPm(String target, String plaintext, PublicKey pubKey) {
        try {
            byte[] aesKey = CryptoUtils.generateAESKey();
            byte[] iv = CryptoUtils.generateIV();
            String encryptedBody = CryptoUtils.encryptAES(plaintext, aesKey, iv);
            String encryptedAesKey = CryptoUtils.encryptRSA(aesKey, pubKey);
            String ivBase64 = java.util.Base64.getEncoder().encodeToString(iv);

            String finalPayload = encryptedAesKey + "|" + ivBase64 + "|" + encryptedBody;
            connection.send(new Message(Protocol.MSG_PRIVATE, username, target, currentRoom, finalPayload));
            synchronized (printLock) {
                System.out.println(BOLD + "[PM to " + colorUsername(target) + "]" + RESET + " " + plaintext);
            }
        } catch (Exception e) {
            printError("Failed to encrypt private message: " + e.getMessage());
        }
    }

    private void handleKickCommand(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /kick <username>");
            return;
        }
        if (username == null) {
            printSystem("Please log in first.");
            return;
        }
        connection.send(new Message(Protocol.MSG_ADMIN_KICK, username, arg, currentRoom, ""));
    }

    private void handleMuteCommand(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /mute <username>");
            return;
        }
        if (username == null) {
            printSystem("Please log in first.");
            return;
        }
        connection.send(new Message(Protocol.MSG_ADMIN_MUTE, username, arg, currentRoom, ""));
    }

    private void handleUnmuteCommand(String arg) {
        if (arg.isEmpty()) {
            printSystem("Usage: /unmute <username>");
            return;
        }
        if (username == null) {
            printSystem("Please log in first.");
            return;
        }
        connection.send(new Message(Protocol.MSG_ADMIN_UNMUTE, username, arg, currentRoom, ""));
    }

    private void quit() {
        running = false;
        if (username != null) {
            connection.send(new Message(Protocol.MSG_QUIT, username, null, currentRoom, ""));
        }
        connection.disconnect();
        printSystem("Goodbye!");
        System.exit(0);
    }

    /**
     * Callback method for formatting and displaying incoming server messages.
     */
    public void onMessageReceived(Message msg) {
        switch (msg.getType()) {
            case Protocol.MSG_ROOM_MSG -> printRoomMessage(msg);
            case Protocol.MSG_HISTORY -> displayHistory(msg);
            case Protocol.MSG_PRESENCE -> printSystem("*** " + msg.getBody() + " ***");
            case Protocol.MSG_LOGIN_OK -> {
                printSystem("Login OK: " + msg.getBody());
                if (msg.getRoom() != null && !msg.getRoom().isBlank()) {
                    currentRoom = msg.getRoom();
                    syncSessionState();
                }
                initializeE2EKeys();
            }
            case Protocol.MSG_LOGIN_FAIL -> printError("Login failed: " + msg.getBody());
            case Protocol.MSG_PONG -> {
                // Heartbeat response
            }
            case Protocol.MSG_ERROR -> printError(msg.getBody());
            case Protocol.MSG_USER_LIST -> printSystem("Online users: " + formatListBody(msg.getBody()));
            case Protocol.MSG_ROOM_LIST -> printSystem("Rooms: " + formatListBody(msg.getBody()));
            case Protocol.MSG_PRIVATE -> printPrivateMessage(msg);
            case Protocol.MSG_KEY_RESP -> handleKeyResponse(msg);
            default -> {
                synchronized (printLock) {
                    System.out.println("[" + msg.getType() + "] "
                            + (msg.getFrom() != null ? colorUsername(msg.getFrom()) + ": " : "")
                            + msg.getBody() + RESET);
                }
            }
        }
    }

    public void onError(String errorMessage) {
        printError(errorMessage);
    }

    public void onReconnectAttempt(int attempt) {
        printSystem("Connection lost. Reconnecting (attempt " + attempt + "/5)...");
    }

    public void onReconnectSuccess() {
        printSystem("Reconnected to server. Session restored for " + colorUsername(username) + ".");
        initializeE2EKeys();
    }

    public void onReconnectFailed() {
        printError("Could not reconnect after 5 attempts. Use /quit to exit.");
    }

    private void initializeE2EKeys() {
        if (username == null) return;
        try {
            java.io.File privFile = new java.io.File("client_private_key_" + username + ".key");
            java.io.File pubFile = new java.io.File("client_public_key_" + username + ".key");
            if (privFile.exists() && pubFile.exists()) {
                String privStr = new String(java.nio.file.Files.readAllBytes(privFile.toPath()), StandardCharsets.UTF_8).trim();
                String pubStr = new String(java.nio.file.Files.readAllBytes(pubFile.toPath()), StandardCharsets.UTF_8).trim();
                this.privateKey = CryptoUtils.decodePrivateKey(privStr);
                this.publicKey = CryptoUtils.decodePublicKey(pubStr);
            } else {
                java.security.KeyPair kp = CryptoUtils.generateRSAKeyPair();
                this.publicKey = kp.getPublic();
                this.privateKey = kp.getPrivate();
                String pubStr = CryptoUtils.encodePublicKey(publicKey);
                String privStr = CryptoUtils.encodePrivateKey(privateKey);
                java.nio.file.Files.write(privFile.toPath(), privStr.getBytes(StandardCharsets.UTF_8));
                java.nio.file.Files.write(pubFile.toPath(), pubStr.getBytes(StandardCharsets.UTF_8));
            }
            // Register public key with server
            String pubBase64 = CryptoUtils.encodePublicKey(publicKey);
            connection.send(new Message(Protocol.MSG_KEY_PUT, username, "server", currentRoom, pubBase64));
        } catch (Exception e) {
            printError("[E2E] Failed to initialize E2E RSA keys: " + e.getMessage());
        }
    }

    private void handleKeyResponse(Message msg) {
        String body = msg.getBody();
        if (body == null || !body.contains("|")) return;
        String[] parts = body.split("\\|", 2);
        String targetUser = parts[0];
        String keyBase64 = parts.length > 1 ? parts[1] : "";

        if (keyBase64.isBlank()) {
            printError("[E2E] User '" + targetUser + "' has not registered public keys. Cannot send PM.");
            pendingPms.remove(targetUser);
            return;
        }

        try {
            PublicKey pubKey = CryptoUtils.decodePublicKey(keyBase64);
            publicKeyCache.put(targetUser, pubKey);
            String pending = pendingPms.remove(targetUser);
            if (pending != null) {
                sendEncryptedPm(targetUser, pending, pubKey);
            }
        } catch (Exception e) {
            printError("[E2E] Failed to parse public key for '" + targetUser + "': " + e.getMessage());
        }
    }

    private void displayHistory(Message msg) {
        String room = msg.getRoom() != null ? msg.getRoom() : currentRoom;
        printSystem("--- Room history (" + room + ") ---");

        if (msg.getBody() != null && msg.getBody().contains("\n")) {
            for (String line : msg.getBody().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                parseHistoryLine(line, room);
            }
        } else if (msg.getFrom() != null || (msg.getBody() != null && !msg.getBody().isBlank())) {
            renderHistoryEntry(msg.getTimestamp(), msg.getFrom(), msg.getBody(), room);
        } else if (msg.getBody() != null && !msg.getBody().isBlank()) {
            printSystem(msg.getBody());
        }

        printSystem("--- End history ---");
    }

    private void parseHistoryLine(String line, String room) {
        String[] parts = line.split("\\|", 3);
        if (parts.length == 3) {
            try {
                long timestamp = Long.parseLong(parts[0].trim());
                renderHistoryEntry(timestamp, parts[1].trim(), parts[2], room);
                return;
            } catch (NumberFormatException ignored) {
            }
        }
        printSystem(line);
    }

    private void renderHistoryEntry(long timestamp, String from, String body, String room) {
        String time = TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
        String sender = from != null ? from : "unknown";
        synchronized (printLock) {
            System.out.println(DIM + time + RESET + " "
                    + DIM + "[" + room + "]" + RESET + " "
                    + colorUsername(sender) + ": "
                    + (body != null ? body : "") + RESET);
        }
    }

    private void printRoomMessage(Message msg) {
        String room = msg.getRoom() != null ? msg.getRoom() : currentRoom;
        String sender = msg.getFrom() != null ? msg.getFrom() : "unknown";
        synchronized (printLock) {
            System.out.println(DIM + "[" + room + "]" + RESET + " "
                    + colorUsername(sender) + ": "
                    + msg.getBody() + RESET);
        }
    }

    private void printPrivateMessage(Message msg) {
        String sender = msg.getFrom() != null ? msg.getFrom() : "unknown";
        String body = msg.getBody();
        if (body != null && body.contains("|")) {
            String[] parts = body.split("\\|", 3);
            if (parts.length == 3) {
                try {
                    String encAesKey = parts[0];
                    String ivBase64 = parts[1];
                    String ciphertext = parts[2];

                    if (privateKey == null) {
                        printError("[E2E] Received encrypted PM from " + colorUsername(sender) + " but local private key is not loaded!");
                        return;
                    }

                    byte[] aesKey = CryptoUtils.decryptRSA(encAesKey, privateKey);
                    byte[] iv = java.util.Base64.getDecoder().decode(ivBase64);
                    String decrypted = CryptoUtils.decryptAES(ciphertext, aesKey, iv);

                    synchronized (printLock) {
                        System.out.println(BOLD + "[PM from " + colorUsername(sender) + "]" + RESET + " " + decrypted);
                    }
                    return;
                } catch (Exception e) {
                    // Fallback to raw output
                }
            }
        }
        synchronized (printLock) {
            System.out.println(BOLD + "[PM from " + colorUsername(sender) + "]" + RESET + " " + body);
        }
    }

    private String formatListBody(String body) {
        if (body == null || body.isBlank()) {
            return "(none)";
        }
        return body;
    }

    private String colorUsername(String name) {
        if (name == null) {
            return "unknown";
        }
        int index = Math.floorMod(name.hashCode(), USER_COLORS.length);
        return USER_COLORS[index] + name;
    }

    private void printSystem(String message) {
        synchronized (printLock) {
            System.out.println(DIM + "[SYSTEM] " + message + RESET);
        }
    }

    private void printError(String message) {
        synchronized (printLock) {
            System.out.println(RED + "[ERROR] " + message + RESET);
        }
    }

    private void printHelp() {
        synchronized (printLock) {
            System.out.println(BOLD + "==================================================" + RESET);
            System.out.println(BOLD + "            DARTS-Java Terminal Client            " + RESET);
            System.out.println(BOLD + "==================================================" + RESET);
            System.out.println(DIM + " Commands:" + RESET);
            System.out.println("   /login <user> [pass]  Log in (password optional in Phase 1)");
            System.out.println("   /register <user> <pass>  Create a new account");
            System.out.println("   /join <room>          Join a chat room");
            System.out.println("   /leave                Leave the current room");
            System.out.println("   /create-room <room>   Create a new room");
            System.out.println("   /pm <user> <message>  Send an E2E encrypted private message");
            System.out.println("   /rooms                List available rooms");
            System.out.println("   /users                List online users");
            System.out.println("   /kick <user>          Admin-only command to kick user");
            System.out.println("   /mute <user>          Admin-only command to mute user");
            System.out.println("   /unmute <user>        Admin-only command to unmute user");
            System.out.println("   /all <message>        Send a message to the current room");
            System.out.println("   <message>             Plain text sends to the current room");
            System.out.println("   /help                 Show this help menu");
            System.out.println("   /quit                 Exit the client");
            System.out.println(DIM + " Tips: Up/Down arrows browse command history." + RESET);
            System.out.println(BOLD + "==================================================" + RESET);
        }
    }

    private String readLineWithHistory() throws IOException {
        InputStream input = System.in;
        StringBuilder current = new StringBuilder();
        int historyIndex = commandHistory.size();

        printPrompt(current);

        while (true) {
            int raw = input.read();
            if (raw == -1) {
                return null;
            }

            if (raw == '\n' || raw == '\r') {
                synchronized (printLock) {
                    System.out.println();
                }
                break;
            }

            if (raw == 9) { // Tab key
                handleAutocomplete(current);
                continue;
            }

            if (raw == 27) {
                int next = input.read();
                int arrow = input.read();
                if (next == '[' && arrow == 'A') {
                    if (!commandHistory.isEmpty() && historyIndex > 0) {
                        historyIndex--;
                        current.setLength(0);
                        current.append(commandHistory.get(historyIndex));
                        redrawLine(current);
                    }
                } else if (next == '[' && arrow == 'B') {
                    if (historyIndex < commandHistory.size() - 1) {
                        historyIndex++;
                        current.setLength(0);
                        current.append(commandHistory.get(historyIndex));
                        redrawLine(current);
                    } else {
                        historyIndex = commandHistory.size();
                        current.setLength(0);
                        redrawLine(current);
                    }
                }
                continue;
            }

            if (raw == 127 || raw == 8) {
                if (!current.isEmpty()) {
                    current.deleteCharAt(current.length() - 1);
                    synchronized (printLock) {
                        System.out.print("\b \b");
                    }
                }
                continue;
            }

            if (raw >= 32 && raw < 127) {
                current.append((char) raw);
                synchronized (printLock) {
                    System.out.print((char) raw);
                }
            }
        }

        String line = current.toString();
        if (!line.isBlank()) {
            if (commandHistory.isEmpty() || !commandHistory.get(commandHistory.size() - 1).equals(line)) {
                commandHistory.add(line);
                if (commandHistory.size() > MAX_HISTORY) {
                    commandHistory.remove(0);
                }
            }
        }
        return line;
    }

    private void printPrompt(StringBuilder current) {
        synchronized (printLock) {
            System.out.print(PROMPT + current);
        }
    }

    private void redrawLine(StringBuilder current) {
        synchronized (printLock) {
            System.out.print("\r\u001B[K" + PROMPT + current);
        }
    }

    private void handleAutocomplete(StringBuilder current) {
        String input = current.toString();
        if (!input.startsWith("/")) {
            return;
        }
        List<String> matches = new ArrayList<>();
        for (String cmd : KNOWN_COMMANDS) {
            if (cmd.startsWith(input)) {
                matches.add(cmd);
            }
        }
        if (matches.isEmpty()) {
            return;
        }
        if (matches.size() == 1) {
            current.setLength(0);
            current.append(matches.get(0)).append(" ");
            redrawLine(current);
        } else {
            synchronized (printLock) {
                System.out.println();
                System.out.println(DIM + String.join("  ", matches) + RESET);
            }
            redrawLine(current);
        }
    }

    private void enableAnsiIfPossible() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo", "off");
                pb.start().waitFor();
            } catch (Exception ignored) {
            }
        }
    }
}
