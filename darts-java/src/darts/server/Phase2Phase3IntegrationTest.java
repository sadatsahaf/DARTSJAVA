package darts.server;

import darts.client.ServerConnection;
import darts.common.Message;
import darts.common.Protocol;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration test suite for Phase 2 & Phase 3.
 * Tests PBKDF2 user registration, authentication, brute-force rate-limiting,
 * message persistence in embedded H2 DB, history replay, and server restart data survival.
 */
public class Phase2Phase3IntegrationTest {
    private static final int TEST_PORT = 9998;
    private static final String TEST_DB_PATH = "./test_phase23_db";

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Phase 2 & 3 Integration Tests...");
        cleanDbFiles();

        try {
            // Test 1: User registration, login, chat persistence, and history replay
            testAuthAndPersistence();

            // Test 2: Server restart data survival
            testServerRestartPersistence();

            // Test 3: Brute force rate limiting
            testRateLimitingLockout();

            System.out.println("[SUCCESS] All Phase 2 & 3 Integration Tests passed clean!");
        } finally {
            cleanDbFiles();
        }
    }

    private static void testAuthAndPersistence() throws Exception {
        System.out.println("Testing user registration, PBKDF2 authentication & message history...");
        Database db = new Database(TEST_DB_PATH);
        Server server = new Server(TEST_PORT, db);
        Thread serverThread = new Thread(server::start, "Phase23Server-1");
        serverThread.start();
        Thread.sleep(1500);

        try {
            // Register User 'Alice' with password
            ServerConnection clientAlice = new ServerConnection("localhost", TEST_PORT);
            CountDownLatch aliceRegLatch = new CountDownLatch(1);
            List<Message> aliceMsgs = Collections.synchronizedList(new ArrayList<>());

            clientAlice.setMessageHandler(msg -> {
                aliceMsgs.add(msg);
                if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                    aliceRegLatch.countDown();
                }
            });
            clientAlice.connect();
            clientAlice.send(new Message(Protocol.MSG_REGISTER, "Alice", null, "general", "AliceSecretPassword123"));

            if (!aliceRegLatch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Alice registration timed out!");
            }
            System.out.println("  -> Alice registered and authenticated successfully.");

            // Alice sends a persisted message
            clientAlice.send(new Message(Protocol.MSG_ROOM_MSG, "Alice", null, "general", "Hello from Alice's persistent chat!"));
            Thread.sleep(300);

            // Connect Bob and register
            ServerConnection clientBob = new ServerConnection("localhost", TEST_PORT);
            CountDownLatch bobHistoryLatch = new CountDownLatch(1);
            List<Message> bobMsgs = Collections.synchronizedList(new ArrayList<>());

            clientBob.setMessageHandler(msg -> {
                bobMsgs.add(msg);
                if (Protocol.MSG_HISTORY.equals(msg.getType())) {
                    bobHistoryLatch.countDown();
                }
            });
            clientBob.connect();
            clientBob.send(new Message(Protocol.MSG_REGISTER, "Bob", null, "general", "BobSecretPassword456"));

            boolean gotHistory = bobHistoryLatch.await(5, TimeUnit.SECONDS);
            clientAlice.disconnect();
            clientBob.disconnect();

            if (!gotHistory) {
                throw new RuntimeException("Bob did not receive MSG_HISTORY on join!");
            }
            System.out.println("  -> MSG_HISTORY replayed successfully to joining client.");
        } finally {
            server.stop();
            serverThread.join(1000);
        }
    }

    private static void testServerRestartPersistence() throws Exception {
        System.out.println("Testing data persistence across server restart...");

        // Re-launch server with same H2 DB path
        Database db = new Database(TEST_DB_PATH);
        Server server = new Server(TEST_PORT, db);
        Thread serverThread = new Thread(server::start, "Phase23Server-2");
        serverThread.start();
        Thread.sleep(1500);

        try {
            // Alice logs in with stored credentials
            ServerConnection clientAlice = new ServerConnection("localhost", TEST_PORT);
            CountDownLatch aliceLoginLatch = new CountDownLatch(1);
            CountDownLatch historyLatch = new CountDownLatch(1);
            List<String> historyLines = Collections.synchronizedList(new ArrayList<>());

            clientAlice.setMessageHandler(msg -> {
                if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                    aliceLoginLatch.countDown();
                }
                if (Protocol.MSG_HISTORY.equals(msg.getType())) {
                    historyLines.add(msg.getBody());
                    historyLatch.countDown();
                }
            });
            clientAlice.connect();
            clientAlice.send(new Message(Protocol.MSG_LOGIN, "Alice", null, "general", "AliceSecretPassword123"));

            if (!aliceLoginLatch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Alice login after server restart failed!");
            }
            System.out.println("  -> Alice account survived server restart.");

            if (!historyLatch.await(5, TimeUnit.SECONDS) || historyLines.isEmpty() || !historyLines.get(0).contains("Hello from Alice's persistent chat!")) {
                throw new RuntimeException("Message history did not survive server restart!");
            }
            System.out.println("  -> Message history survived server restart.");

            clientAlice.disconnect();
        } finally {
            server.stop();
            serverThread.join(1000);
        }
    }

    private static void testRateLimitingLockout() throws Exception {
        System.out.println("Testing brute-force rate-limiting lockout...");

        Database db = new Database(TEST_DB_PATH);
        Server server = new Server(TEST_PORT, db);
        Thread serverThread = new Thread(server::start, "Phase23Server-3");
        serverThread.start();
        Thread.sleep(1500);

        try {
            // Send 5 bad login attempts for user 'Alice'
            for (int i = 1; i <= 5; i++) {
                ServerConnection attacker = new ServerConnection("localhost", TEST_PORT);
                CountDownLatch failLatch = new CountDownLatch(1);
                attacker.setMessageHandler(msg -> {
                    if (Protocol.MSG_LOGIN_FAIL.equals(msg.getType())) {
                        failLatch.countDown();
                    }
                });
                attacker.connect();
                attacker.send(new Message(Protocol.MSG_LOGIN, "Alice", null, "general", "WrongPass" + i));
                failLatch.await(3, TimeUnit.SECONDS);
                attacker.disconnect();
            }

            // 6th attempt should be blocked by rate limiter
            ServerConnection attacker = new ServerConnection("localhost", TEST_PORT);
            CountDownLatch rateLimitLatch = new CountDownLatch(1);
            List<Message> responses = Collections.synchronizedList(new ArrayList<>());
            attacker.setMessageHandler(msg -> {
                responses.add(msg);
                if (Protocol.MSG_LOGIN_FAIL.equals(msg.getType()) && msg.getBody().contains("Too many failed login attempts")) {
                    rateLimitLatch.countDown();
                }
            });
            attacker.connect();
            attacker.send(new Message(Protocol.MSG_LOGIN, "Alice", null, "general", "WrongPass6"));

            boolean rateLimited = rateLimitLatch.await(5, TimeUnit.SECONDS);
            attacker.disconnect();

            if (!rateLimited) {
                throw new RuntimeException("Brute force rate limiting did not trigger lockout after 5 failures!");
            }
            System.out.println("  -> 60-second brute-force lockout triggered correctly after 5 failures.");
        } finally {
            server.stop();
            serverThread.join(1000);
        }
    }

    private static void cleanDbFiles() {
        File dbFile = new File(TEST_DB_PATH + ".mv.db");
        if (dbFile.exists()) dbFile.delete();
        File traceFile = new File(TEST_DB_PATH + ".trace.db");
        if (traceFile.exists()) traceFile.delete();
    }
}
