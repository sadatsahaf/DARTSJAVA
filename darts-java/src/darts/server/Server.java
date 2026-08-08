package darts.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main server entry point implementing a single-threaded non-blocking Selector event loop,
 * with background ExecutorService worker pool for DB queries and PBKDF2 password hashing.
 */
public class Server {
    public static final int DEFAULT_PORT = 8888;

    private final int port;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Database database;
    private final AuthManager authManager;
    private ExecutorService workerPool;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private volatile boolean running = false;

    public Server(int port, Database database) {
        this.port = port;
        this.database = database;
        this.authManager = new AuthManager(database);
        this.workerPool = Executors.newFixedThreadPool(4);
        rooms.put("general", new Room("general"));
    }

    public Server(int port) {
        this(port, new Database());
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number specified, defaulting to " + DEFAULT_PORT);
            }
        }

        Server server = new Server(port);
        server.start();
    }

    public Database getDatabase() {
        return database;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public synchronized ExecutorService getWorkerPool() {
        if (workerPool == null || workerPool.isShutdown()) {
            workerPool = Executors.newFixedThreadPool(4);
        }
        return workerPool;
    }

    /**
     * Initializes Database, ServerSocketChannel, binds port, and enters the Selector loop.
     */
    public void start() {
        try {
            System.out.println("Initializing embedded H2 database...");
            database.init();

            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            running = true;
            System.out.println("DARTS Server started on port " + port + ". Event loop running...");

            long lastSweepTime = System.currentTimeMillis();

            while (running) {
                int selected = selector.select(5000);
                long now = System.currentTimeMillis();

                // Periodic 5s sweep for idle connections
                if (now - lastSweepTime > 5000) {
                    lastSweepTime = now;
                    for (SelectionKey key : selector.keys()) {
                        if (key.isValid() && key.attachment() instanceof ClientSession session) {
                            session.checkIdleTimeout(now);
                        }
                    }
                }

                if (selected == 0) {
                    continue;
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            acceptConnection(key);
                        } else if (key.isReadable()) {
                            ClientSession session = (ClientSession) key.attachment();
                            if (session != null) {
                                session.handleRead();
                            }
                        } else if (key.isWritable()) {
                            ClientSession session = (ClientSession) key.attachment();
                            if (session != null) {
                                session.handleWrite();
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Exception handling selection key: " + e.getMessage());
                        ClientSession session = (ClientSession) key.attachment();
                        if (session != null) {
                            session.close();
                        } else {
                            key.cancel();
                        }
                    }
                }
            }
        } catch (ClosedSelectorException ignored) {
            // Normal shutdown when selector is closed
        } catch (SQLException e) {
            System.err.println("Database initialization error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            stop();
        }
    }

    private void acceptConnection(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverSocketChannel.accept();
        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            ClientSession session = new ClientSession(clientChannel, clientKey, this);
            clientKey.attach(session);
            System.out.println("Accepted new connection from " + clientChannel.getRemoteAddress());
        }
    }

    public Room getRoom(String name) {
        return rooms.computeIfAbsent(name, Room::new);
    }

    public ClientSession getSessionByUsername(String username) {
        if (username == null || selector == null || !selector.isOpen()) {
            return null;
        }
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ClientSession session) {
                if (username.equals(session.getUsername())) {
                    return session;
                }
            }
        }
        return null;
    }

    public void stop() {
        running = false;
        if (workerPool != null && !workerPool.isShutdown()) {
            workerPool.shutdown();
        }
        if (selector != null && selector.isOpen()) {
            try {
                for (SelectionKey key : selector.keys()) {
                    if (key.attachment() instanceof ClientSession session) {
                        session.close();
                    }
                }
                selector.close();
                if (serverChannel != null && serverChannel.isOpen()) {
                    serverChannel.close();
                }
                System.out.println("DARTS Server stopped successfully.");
            } catch (IOException e) {
                System.err.println("Error closing server: " + e.getMessage());
            }
        }
    }
}
