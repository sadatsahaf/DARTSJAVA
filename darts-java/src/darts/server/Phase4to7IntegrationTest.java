package darts.server;

import darts.client.ServerConnection;
import darts.common.Message;
import darts.common.Protocol;
import darts.common.CryptoUtils;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end integration test suite verifying:
 * 1. Dynamic Room Creation and Multi-room join/leave navigation.
 * 2. Presence broadcasting and user list updates.
 * 3. Server-side Admin Kicks.
 * 4. End-to-End Encrypted Private Messages (RSA-AES hybrid).
 */
public class Phase4to7IntegrationTest {
    private static final int TEST_PORT = 9993;
    private static final String TEST_DB_PATH = "./test_phase47_db";

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Phase 4-7 Integration Tests...");
        cleanDbFiles();

        Database db = new Database(TEST_DB_PATH);
        Server server = new Server(TEST_PORT, db);
        Thread serverThread = new Thread(server::start, "Phase47Server");
        serverThread.start();
        Thread.sleep(1500); // Allow database and server to initialize

        try {
            // Seed an admin user "AdminAlice" directly into the DB
            String salt = CryptoUtils.generateSalt();
            String hash = CryptoUtils.hashPassword("AdminPass123", salt);
            db.createUser("AdminAlice", hash, salt, true);

            // Seed normal user "Bob"
            String bobSalt = CryptoUtils.generateSalt();
            String bobHash = CryptoUtils.hashPassword("BobPass456", bobSalt);
            db.createUser("Bob", bobHash, bobSalt, false);

            testRoomsAndPresence(db);
            testAdminKick(db);
            testAdminMute(db);
            testE2EPrivateMessaging(db);

            System.out.println("[SUCCESS] All Phase 4-7 Integration Tests passed clean!");
        } finally {
            server.stop();
            serverThread.join(1000);
            cleanDbFiles();
        }
    }

    private static void testRoomsAndPresence(Database db) throws Exception {
        System.out.println("Testing room creation, multi-room join/leave & user lists...");

        CountDownLatch aliceJoinLatch = new CountDownLatch(1);
        CountDownLatch roomListLatch = new CountDownLatch(1);
        List<Message> aliceMsgs = Collections.synchronizedList(new ArrayList<>());

        ServerConnection alice = new ServerConnection("localhost", TEST_PORT);
        alice.setMessageHandler(msg -> {
            aliceMsgs.add(msg);
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                aliceJoinLatch.countDown();
            }
            if (Protocol.MSG_ROOM_LIST.equals(msg.getType())) {
                roomListLatch.countDown();
            }
        });
        alice.connect();
        alice.send(new Message(Protocol.MSG_LOGIN, "AdminAlice", null, "general", "AdminPass123"));
        aliceJoinLatch.await(5, TimeUnit.SECONDS);

        // 1. Create a custom room "dev-room"
        alice.send(new Message(Protocol.MSG_CREATE_ROOM, "AdminAlice", null, "general", "dev-room"));
        Thread.sleep(500);

        // 2. Request room listing
        alice.send(new Message(Protocol.MSG_ROOM_LIST, "AdminAlice", null, "general", ""));
        boolean gotRooms = roomListLatch.await(5, TimeUnit.SECONDS);

        if (!gotRooms) {
            throw new RuntimeException("Alice did not receive MSG_ROOM_LIST response!");
        }

        // Verify "dev-room" is present in the list
        boolean roomExists = aliceMsgs.stream()
                .filter(m -> Protocol.MSG_ROOM_LIST.equals(m.getType()))
                .anyMatch(m -> m.getBody().contains("dev-room"));
        if (!roomExists) {
            throw new RuntimeException("Created room 'dev-room' was not in the room list!");
        }
        System.out.println("  -> Room creation and list lookup successful.");

        // 3. Connect Bob and join dev-room
        CountDownLatch bobJoinRoomLatch = new CountDownLatch(1);
        CountDownLatch bobUserListLatch = new CountDownLatch(1);
        List<Message> bobMsgs = Collections.synchronizedList(new ArrayList<>());

        ServerConnection bob = new ServerConnection("localhost", TEST_PORT);
        bob.setMessageHandler(msg -> {
            bobMsgs.add(msg);
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                bob.send(new Message(Protocol.MSG_JOIN_ROOM, "Bob", null, "dev-room", ""));
            }
            if (Protocol.MSG_HISTORY.equals(msg.getType()) && "dev-room".equals(msg.getRoom())) {
                bobJoinRoomLatch.countDown();
            }
            if (Protocol.MSG_USER_LIST.equals(msg.getType())) {
                bobUserListLatch.countDown();
            }
        });
        bob.connect();
        bob.send(new Message(Protocol.MSG_LOGIN, "Bob", null, "general", "BobPass456"));
        bobJoinRoomLatch.await(5, TimeUnit.SECONDS);

        // Alice joins dev-room as well
        alice.send(new Message(Protocol.MSG_JOIN_ROOM, "AdminAlice", null, "dev-room", ""));
        Thread.sleep(500);

        // 4. Bob checks user listing in dev-room
        bob.send(new Message(Protocol.MSG_USER_LIST, "Bob", null, "dev-room", ""));
        bobUserListLatch.await(5, TimeUnit.SECONDS);

        boolean usersFound = bobMsgs.stream()
                .filter(m -> Protocol.MSG_USER_LIST.equals(m.getType()))
                .anyMatch(m -> m.getBody().contains("AdminAlice") && m.getBody().contains("Bob"));

        if (!usersFound) {
            throw new RuntimeException("Online user list did not correctly show Alice and Bob in dev-room");
        }
        System.out.println("  -> Multi-room navigation & user listing verified.");

        alice.disconnect();
        bob.disconnect();
    }

    private static void testAdminKick(Database db) throws Exception {
        System.out.println("Testing admin moderation kick...");

        CountDownLatch aliceLogin = new CountDownLatch(1);
        ServerConnection alice = new ServerConnection("localhost", TEST_PORT);
        alice.setMessageHandler(msg -> {
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                aliceLogin.countDown();
            }
        });
        alice.connect();
        alice.send(new Message(Protocol.MSG_LOGIN, "AdminAlice", null, "general", "AdminPass123"));
        aliceLogin.await(5, TimeUnit.SECONDS);

        CountDownLatch bobLogin = new CountDownLatch(1);
        CountDownLatch bobKicked = new CountDownLatch(1);
        ServerConnection bob = new ServerConnection("localhost", TEST_PORT);
        bob.setMessageHandler(msg -> {
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                bobLogin.countDown();
            }
            if (Protocol.MSG_ERROR.equals(msg.getType()) && msg.getBody().contains("kicked")) {
                bobKicked.countDown();
            }
        });
        bob.connect();
        bob.send(new Message(Protocol.MSG_LOGIN, "Bob", null, "general", "BobPass456"));
        bobLogin.await(5, TimeUnit.SECONDS);

        // Admin Alice kicks Bob
        alice.send(new Message(Protocol.MSG_ADMIN_KICK, "AdminAlice", "Bob", "general", ""));

        boolean kicked = bobKicked.await(5, TimeUnit.SECONDS);
        alice.disconnect();
        bob.disconnect();

        if (!kicked) {
            throw new RuntimeException("Bob was not disconnected by Admin kick command!");
        }
        System.out.println("  -> Admin kick command executed successfully.");
    }

    private static void testAdminMute(Database db) throws Exception {
        System.out.println("Testing admin mute/unmute moderation...");

        CountDownLatch aliceLogin = new CountDownLatch(1);
        ServerConnection alice = new ServerConnection("localhost", TEST_PORT);
        alice.setMessageHandler(msg -> {
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                aliceLogin.countDown();
            }
        });
        alice.connect();
        alice.send(new Message(Protocol.MSG_LOGIN, "AdminAlice", null, "general", "AdminPass123"));
        aliceLogin.await(5, TimeUnit.SECONDS);

        CountDownLatch bobLogin = new CountDownLatch(1);
        CountDownLatch bobMuted = new CountDownLatch(1);
        CountDownLatch bobUnmuted = new CountDownLatch(1);
        CountDownLatch bobMsgBlocked = new CountDownLatch(1);
        List<Message> bobMsgs = Collections.synchronizedList(new ArrayList<>());

        ServerConnection bob = new ServerConnection("localhost", TEST_PORT);
        bob.setMessageHandler(msg -> {
            bobMsgs.add(msg);
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                bobLogin.countDown();
            }
            if (Protocol.MSG_PRESENCE.equals(msg.getType()) && msg.getBody() != null
                    && msg.getBody().contains("Bob") && msg.getBody().contains("muted by")) {
                bobMuted.countDown();
            }
            if (Protocol.MSG_PRESENCE.equals(msg.getType()) && msg.getBody() != null
                    && msg.getBody().contains("Bob") && msg.getBody().contains("unmuted by")) {
                bobUnmuted.countDown();
            }
            if (Protocol.MSG_ERROR.equals(msg.getType()) && msg.getBody() != null
                    && msg.getBody().contains("muted")) {
                bobMsgBlocked.countDown();
            }
        });
        bob.connect();
        bob.send(new Message(Protocol.MSG_LOGIN, "Bob", null, "general", "BobPass456"));
        bobLogin.await(5, TimeUnit.SECONDS);

        // Admin mutes Bob
        alice.send(new Message(Protocol.MSG_ADMIN_MUTE, "AdminAlice", "Bob", "general", ""));
        boolean muted = bobMuted.await(5, TimeUnit.SECONDS);
        if (!muted) {
            throw new RuntimeException("Bob did not receive mute notification!");
        }
        System.out.println("  -> Mute notification received.");

        // Bob tries to send a message while muted
        bob.send(new Message(Protocol.MSG_ROOM_MSG, "Bob", null, "general", "This should be blocked"));
        boolean blocked = bobMsgBlocked.await(5, TimeUnit.SECONDS);
        if (!blocked) {
            throw new RuntimeException("Bob's message was not blocked while muted!");
        }
        System.out.println("  -> Muted message correctly blocked.");

        // Admin unmutes Bob
        alice.send(new Message(Protocol.MSG_ADMIN_UNMUTE, "AdminAlice", "Bob", "general", ""));
        boolean unmuted = bobUnmuted.await(5, TimeUnit.SECONDS);
        if (!unmuted) {
            throw new RuntimeException("Bob did not receive unmute notification!");
        }
        System.out.println("  -> Unmute notification received.");

        // Bob sends a message after unmute — should succeed (no error)
        CountDownLatch bobMsgSuccess = new CountDownLatch(1);
        alice.setMessageHandler(msg -> {
            if (Protocol.MSG_ROOM_MSG.equals(msg.getType()) && msg.getBody() != null
                    && msg.getBody().contains("I can talk again")) {
                bobMsgSuccess.countDown();
            }
        });
        bob.send(new Message(Protocol.MSG_ROOM_MSG, "Bob", null, "general", "I can talk again"));
        boolean success = bobMsgSuccess.await(5, TimeUnit.SECONDS);
        if (!success) {
            throw new RuntimeException("Bob's message after unmute was not delivered!");
        }
        System.out.println("  -> Post-unmute message delivered successfully.");

        alice.disconnect();
        bob.disconnect();
    }

    private static void testE2EPrivateMessaging(Database db) throws Exception {
        System.out.println("Testing End-to-End Encrypted DMs (E2E key handshakes)...");

        // 1. Set up RSA keypairs for Alice and Bob
        KeyPair aliceKeys = CryptoUtils.generateRSAKeyPair();
        KeyPair bobKeys = CryptoUtils.generateRSAKeyPair();

        CountDownLatch aliceLogin = new CountDownLatch(1);
        CountDownLatch aliceGotKey = new CountDownLatch(1);
        List<Message> aliceMsgs = Collections.synchronizedList(new ArrayList<>());

        ServerConnection alice = new ServerConnection("localhost", TEST_PORT);
        alice.setMessageHandler(msg -> {
            aliceMsgs.add(msg);
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                // Register Alice's Public Key
                String keyStr = CryptoUtils.encodePublicKey(aliceKeys.getPublic());
                alice.send(new Message(Protocol.MSG_KEY_PUT, "AdminAlice", "server", "general", keyStr));
                aliceLogin.countDown();
            }
            if (Protocol.MSG_KEY_RESP.equals(msg.getType())) {
                aliceGotKey.countDown();
            }
        });
        alice.connect();
        alice.send(new Message(Protocol.MSG_LOGIN, "AdminAlice", null, "general", "AdminPass123"));
        aliceLogin.await(5, TimeUnit.SECONDS);

        CountDownLatch bobLogin = new CountDownLatch(1);
        CountDownLatch bobGotPm = new CountDownLatch(1);
        List<Message> bobMsgs = Collections.synchronizedList(new ArrayList<>());

        ServerConnection bob = new ServerConnection("localhost", TEST_PORT);
        bob.setMessageHandler(msg -> {
            bobMsgs.add(msg);
            if (Protocol.MSG_LOGIN_OK.equals(msg.getType())) {
                // Register Bob's Public Key
                String keyStr = CryptoUtils.encodePublicKey(bobKeys.getPublic());
                bob.send(new Message(Protocol.MSG_KEY_PUT, "Bob", "server", "general", keyStr));
                bobLogin.countDown();
            }
            if (Protocol.MSG_PRIVATE.equals(msg.getType())) {
                bobGotPm.countDown();
            }
        });
        bob.connect();
        bob.send(new Message(Protocol.MSG_LOGIN, "Bob", null, "general", "BobPass456"));
        bobLogin.await(5, TimeUnit.SECONDS);

        // Sleep to ensure Bob's public key is fully registered
        Thread.sleep(500);

        // 2. Alice requests Bob's Public Key
        alice.send(new Message(Protocol.MSG_KEY_GET, "AdminAlice", "Bob", "general", "Bob"));
        aliceGotKey.await(5, TimeUnit.SECONDS);

        // Extract key from response
        Message keyResp = aliceMsgs.stream()
                .filter(m -> Protocol.MSG_KEY_RESP.equals(m.getType()))
                .findFirst().orElseThrow(() -> new RuntimeException("No key response received"));

        String body = keyResp.getBody();
        String[] parts = body.split("\\|", 2);
        String targetName = parts[0];
        String keyBase64 = parts[1];

        if (!"Bob".equals(targetName) || keyBase64.isEmpty()) {
            throw new RuntimeException("Invalid public key response payload");
        }

        PublicKey bobsPubKey = CryptoUtils.decodePublicKey(keyBase64);

        // 3. Alice encrypts PM for Bob
        String secretPlaintext = "Top Secret Information!";
        byte[] aesKey = CryptoUtils.generateAESKey();
        byte[] iv = CryptoUtils.generateIV();

        String encryptedBody = CryptoUtils.encryptAES(secretPlaintext, aesKey, iv);
        String encryptedAesKey = CryptoUtils.encryptRSA(aesKey, bobsPubKey);
        String ivBase64 = java.util.Base64.getEncoder().encodeToString(iv);

        String finalPayload = encryptedAesKey + "|" + ivBase64 + "|" + encryptedBody;

        // Verify that the payload does NOT contain the plaintext
        if (finalPayload.contains(secretPlaintext)) {
            throw new RuntimeException("Security Leak: Wire payload contains E2E plaintext!");
        }

        // Alice sends PM to Bob
        alice.send(new Message(Protocol.MSG_PRIVATE, "AdminAlice", "Bob", "general", finalPayload));
        bobGotPm.await(5, TimeUnit.SECONDS);

        // 4. Bob decrypts PM
        Message pmReceived = bobMsgs.stream()
                .filter(m -> Protocol.MSG_PRIVATE.equals(m.getType()))
                .findFirst().orElseThrow(() -> new RuntimeException("Bob did not receive PM"));

        String receivedPayload = pmReceived.getBody();
        String[] pmParts = receivedPayload.split("\\|", 3);

        byte[] decryptedAesKey = CryptoUtils.decryptRSA(pmParts[0], bobKeys.getPrivate());
        byte[] decryptedIv = java.util.Base64.getDecoder().decode(pmParts[1]);
        String decryptedPlaintext = CryptoUtils.decryptAES(pmParts[2], decryptedAesKey, decryptedIv);

        if (!secretPlaintext.equals(decryptedPlaintext)) {
            throw new RuntimeException("Decrypted text '" + decryptedPlaintext + "' does not match original plaintext!");
        }
        System.out.println("  -> Hybrid RSA-AES E2E decryption successful.");

        alice.disconnect();
        bob.disconnect();
    }

    private static void cleanDbFiles() {
        File dbFile = new File(TEST_DB_PATH + ".mv.db");
        if (dbFile.exists()) dbFile.delete();
        File traceFile = new File(TEST_DB_PATH + ".trace.db");
        if (traceFile.exists()) dbFile.delete();
    }
}
