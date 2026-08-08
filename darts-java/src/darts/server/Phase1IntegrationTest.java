package darts.server;

import darts.client.ServerConnection;
import darts.common.Message;
import darts.common.Protocol;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Verification test suite for Phase 1: Core Networking & Protocol.
 * Spawns server instance, connects multiple clients, verifies broadcast chat end-to-end,
 * and tests malformed input resiliency.
 */
public class Phase1IntegrationTest {
    private static final int TEST_PORT = 9995;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Phase 1 Integration Test...");

        // 1. Launch Server in background thread with dedicated test DB
        Database db = new Database("./test_phase1_db");
        Server server = new Server(TEST_PORT, db);
        Thread serverThread = new Thread(server::start, "TestServer");
        serverThread.start();
        Thread.sleep(1500); // Allow server to initialize H2 DB and start selector loop

        try {
            // 2. Test multi-client unauthenticated broadcast chat
            testBroadcastChat();

            // 3. Test malformed payload / oversized frame resiliency
            testMalformedFrameResiliency();

            System.out.println("[SUCCESS] Phase 1 Integration Tests passed clean!");
        } finally {
            server.stop();
            serverThread.join(1000);
            new java.io.File("./test_phase1_db.mv.db").delete();
            new java.io.File("./test_phase1_db.trace.db").delete();
        }
    }

    private static void testBroadcastChat() throws Exception {
        System.out.println("Testing multi-client broadcast chat...");

        List<Message> aliceReceived = Collections.synchronizedList(new ArrayList<>());
        List<Message> bobReceived = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        ServerConnection clientAlice = new ServerConnection("localhost", TEST_PORT);
        clientAlice.setMessageHandler(msg -> {
            aliceReceived.add(msg);
            if (msg.getType().equals(Protocol.MSG_ROOM_MSG) && msg.getBody().contains("Hello from Bob")) {
                latch.countDown();
            }
        });
        clientAlice.connect();
        clientAlice.send(new Message(Protocol.MSG_LOGIN, "Alice", null, "general", ""));

        ServerConnection clientBob = new ServerConnection("localhost", TEST_PORT);
        clientBob.setMessageHandler(msg -> {
            bobReceived.add(msg);
            if (msg.getType().equals(Protocol.MSG_ROOM_MSG) && msg.getBody().contains("Hello from Alice")) {
                latch.countDown();
            }
        });
        clientBob.connect();
        clientBob.send(new Message(Protocol.MSG_LOGIN, "Bob", null, "general", ""));

        Thread.sleep(300);

        // Alice sends message to room
        clientAlice.send(new Message(Protocol.MSG_ROOM_MSG, "Alice", null, "general", "Hello from Alice!"));

        // Bob sends message to room
        clientBob.send(new Message(Protocol.MSG_ROOM_MSG, "Bob", null, "general", "Hello from Bob!"));

        boolean success = latch.await(5, TimeUnit.SECONDS);

        clientAlice.disconnect();
        clientBob.disconnect();

        if (!success) {
            throw new RuntimeException("Broadcast test timed out! Messages were not delivered cleanly across clients.");
        }
        System.out.println("  -> Broadcast messages received correctly by all clients.");
    }

    private static void testMalformedFrameResiliency() throws Exception {
        System.out.println("Testing server resiliency against malformed/oversized frames...");

        // Connect raw socket and send invalid frame length claiming 100,000 bytes (> 64KB max bound)
        try (Socket rawSocket = new Socket("localhost", TEST_PORT);
             DataOutputStream out = new DataOutputStream(rawSocket.getOutputStream());
             DataInputStream in = new DataInputStream(rawSocket.getInputStream())) {

            out.writeInt(100000); // 100KB > 64KB
            out.write(new byte[100]);
            out.flush();

            // Sever should respond with MSG_ERROR or disconnect bad client
            rawSocket.setSoTimeout(2000);
            try {
                int len = in.readInt();
                if (len > 0 && len <= Protocol.MAX_PAYLOAD_SIZE) {
                    byte[] payload = new byte[len];
                    in.readFully(payload);
                    String json = new String(payload, StandardCharsets.UTF_8);
                    System.out.println("  -> Server properly responded with error frame: " + json);
                }
            } catch (Exception ignored) {
                System.out.println("  -> Server safely closed connection for oversized frame.");
            }
        }

        // Verify server is still alive and responsive to valid clients!
        ServerConnection healthCheck = new ServerConnection("localhost", TEST_PORT);
        CountDownLatch pongLatch = new CountDownLatch(1);
        healthCheck.setMessageHandler(msg -> {
            if (Protocol.MSG_PONG.equals(msg.getType())) {
                pongLatch.countDown();
            }
        });
        healthCheck.connect();
        healthCheck.send(new Message(Protocol.MSG_PING, "checker", null, "general", ""));

        boolean alive = pongLatch.await(3, TimeUnit.SECONDS);
        healthCheck.disconnect();

        if (!alive) {
            throw new RuntimeException("Server crashed or hung after receiving malformed frame!");
        }
        System.out.println("  -> Server remained 100% healthy and operational following attack simulation.");
    }
}
