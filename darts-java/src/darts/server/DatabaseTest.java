package darts.server;

import darts.common.Message;
import java.io.File;
import java.util.List;

/**
 * Integration unit test suite for Database H2 tables, PreparedStatement queries, and history replay.
 */
public class DatabaseTest {
    private static final String TEST_DB_PATH = "./test_darts_db";

    public static void main(String[] args) throws Exception {
        System.out.println("Running Database unit tests...");
        cleanDbFiles();

        Database db = new Database(TEST_DB_PATH);
        db.init();

        try {
            testRoomSeeding(db);
            testUserPersistence(db);
            testMessagePersistenceAndHistory(db);
            testAuditLog(db);

            System.out.println("[SUCCESS] All Database unit tests passed!");
        } finally {
            cleanDbFiles();
        }
    }

    private static void testRoomSeeding(Database db) throws Exception {
        Database.RoomRecord general = db.getRoomByName("general");
        if (general == null) {
            throw new RuntimeException("Default 'general' room was not seeded");
        }
    }

    private static void testUserPersistence(Database db) throws Exception {
        Database.UserRecord alice = db.createUser("alice", "hash123", "salt123", false);
        if (alice == null || alice.id() <= 0) {
            throw new RuntimeException("Failed to create user record for Alice");
        }

        Database.UserRecord fetched = db.getUserByUsername("alice");
        if (fetched == null || !fetched.username().equals("alice")) {
            throw new RuntimeException("Failed to retrieve user record for Alice");
        }

        db.updateLastLogin(alice.id());
        fetched = db.getUserByUsername("alice");
        if (fetched.lastLogin() == null) {
            throw new RuntimeException("Expected last_login timestamp to be set");
        }

        db.updatePublicKey(alice.id(), "Base64PublicKeyAlice");
        String pubKey = db.getPublicKey("alice");
        if (!"Base64PublicKeyAlice".equals(pubKey)) {
            throw new RuntimeException("Expected public key Base64PublicKeyAlice, got " + pubKey);
        }

        Database.RoomRecord customRoom = db.createRoom("dev", alice.id());
        if (customRoom == null || !customRoom.name().equals("dev")) {
            throw new RuntimeException("Failed to create room 'dev'");
        }
    }

    private static void testMessagePersistenceAndHistory(Database db) throws Exception {
        Database.UserRecord alice = db.getUserByUsername("alice");
        Database.UserRecord bob = db.createUser("bob", "hash456", "salt456", false);
        Integer generalRoomId = db.getRoomId("general");

        for (int i = 1; i <= 60; i++) {
            int senderId = (i % 2 == 0) ? alice.id() : bob.id();
            db.saveMessage(generalRoomId, senderId, null, "Message #" + i);
        }

        // Fetch recent messages with 50 limit
        List<Message> history = db.getRecentRoomMessages("general", 50);
        if (history.size() != 50) {
            throw new RuntimeException("Expected 50 recent messages, got " + history.size());
        }

        // Verify chronological ordering (first should be #11, last should be #60)
        if (!history.get(0).getBody().equals("Message #11")) {
            throw new RuntimeException("Expected first history item to be Message #11, got: " + history.get(0).getBody());
        }
        if (!history.get(49).getBody().equals("Message #60")) {
            throw new RuntimeException("Expected last history item to be Message #60, got: " + history.get(49).getBody());
        }
    }

    private static void testAuditLog(Database db) throws Exception {
        Database.UserRecord alice = db.getUserByUsername("alice");
        db.logAuditEvent(alice.id(), "TEST_EVENT", "Detail payload");
    }

    private static void cleanDbFiles() {
        File dbFile = new File(TEST_DB_PATH + ".mv.db");
        if (dbFile.exists()) dbFile.delete();
        File traceFile = new File(TEST_DB_PATH + ".trace.db");
        if (traceFile.exists()) traceFile.delete();
    }
}
