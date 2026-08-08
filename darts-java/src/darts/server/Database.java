package darts.server;

import darts.common.Message;
import darts.common.Protocol;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Embedded H2 Database persistence manager. Handles user accounts, room registry,
 * message history logging, and security audit logs using PreparedStatement queries.
 */
public class Database {

    public record UserRecord(
            int id,
            String username,
            String passwordHash,
            String salt,
            boolean isAdmin,
            String publicKey,
            Timestamp createdAt,
            Timestamp lastLogin
    ) {}

    public record RoomRecord(
            int id,
            String name,
            Integer createdBy,
            Timestamp createdAt
    ) {}

    private final String jdbcUrl;

    public Database(String dbPath) {
        this.jdbcUrl = "jdbc:h2:" + dbPath + ";DB_CLOSE_DELAY=-1";
    }

    public Database() {
        this("./darts");
    }

    /**
     * Initializes the H2 database, creates schema tables if missing, and seeds default rooms.
     */
    public synchronized void init() throws SQLException {
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 JDBC Driver not found in classpath", e);
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. users table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(32) UNIQUE NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    salt VARCHAR(64) NOT NULL,
                    is_admin BOOLEAN DEFAULT FALSE,
                    public_key VARCHAR(1024),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    last_login TIMESTAMP
                );
            """);

            // Migration to add public_key column to older database schemas if present
            try {
                stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS public_key VARCHAR(1024)");
            } catch (SQLException ignored) {
                // Already exists or unsupported
            }

            // 2. rooms table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS rooms (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(64) UNIQUE NOT NULL,
                    created_by INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL
                );
            """);

            // 3. messages table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    room_id INT,
                    sender_id INT NOT NULL,
                    recipient_id INT,
                    body VARCHAR(2048) NOT NULL,
                    sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE CASCADE,
                    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE,
                    FOREIGN KEY (recipient_id) REFERENCES users(id) ON DELETE CASCADE
                );
            """);

            // 4. audit_log table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT,
                    event VARCHAR(64) NOT NULL,
                    detail VARCHAR(255),
                    occurred_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
                );
            """);

            // Seed default 'general' room
            seedRoom(conn, "general");
        }
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    private void seedRoom(Connection conn, String roomName) throws SQLException {
        String checkSql = "SELECT id FROM rooms WHERE name = ?";
        try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setString(1, roomName);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (!rs.next()) {
                    String insertSql = "INSERT INTO rooms (name) VALUES (?)";
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, roomName);
                        insertStmt.executeUpdate();
                    }
                }
            }
        }
    }

    /**
     * Creates a new user record and returns the created UserRecord instance.
     */
    public UserRecord createUser(String username, String passwordHash, String salt, boolean isAdmin) throws SQLException {
        String sql = "INSERT INTO users (username, password_hash, salt, is_admin) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, username);
            stmt.setString(2, passwordHash);
            stmt.setString(3, salt);
            stmt.setBoolean(4, isAdmin);
            stmt.executeUpdate();
        }
        return getUserByUsername(username);
    }

    /**
     * Retrieves user record by username, or null if not found.
     */
    public UserRecord getUser(String username) throws SQLException {
        return getUserByUsername(username);
    }

    /**
     * Alias for getUser(username).
     */
    public UserRecord getUserByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, password_hash, salt, is_admin, public_key, created_at, last_login FROM users WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new UserRecord(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("salt"),
                            rs.getBoolean("is_admin"),
                            rs.getString("public_key"),
                            rs.getTimestamp("created_at"),
                            rs.getTimestamp("last_login")
                    );
                }
            }
        }
        return null;
    }

    public void updatePublicKey(int userId, String publicKeyBase64) throws SQLException {
        String sql = "UPDATE users SET public_key = ? WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, publicKeyBase64);
            stmt.setInt(2, userId);
            stmt.executeUpdate();
        }
    }

    public String getPublicKey(String username) throws SQLException {
        String sql = "SELECT public_key FROM users WHERE username = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("public_key");
                }
            }
        }
        return null;
    }

    public void updateLastLogin(int userId) throws SQLException {
        String sql = "UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, userId);
            stmt.executeUpdate();
        }
    }

    public RoomRecord createRoom(String roomName, Integer createdByUserId) throws SQLException {
        String sql = "INSERT INTO rooms (name, created_by) VALUES (?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, roomName);
            if (createdByUserId != null) {
                stmt.setInt(2, createdByUserId);
            } else {
                stmt.setNull(2, java.sql.Types.INTEGER);
            }
            stmt.executeUpdate();
        }
        return getRoomByName(roomName);
    }

    public RoomRecord getRoomByName(String roomName) throws SQLException {
        String sql = "SELECT id, name, created_by, created_at FROM rooms WHERE name = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, roomName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Integer createdBy = rs.getInt("created_by");
                    if (rs.wasNull()) {
                        createdBy = null;
                    }
                    return new RoomRecord(
                            rs.getInt("id"),
                            rs.getString("name"),
                            createdBy,
                            rs.getTimestamp("created_at")
                    );
                }
            }
        }
        return null;
    }

    public Integer getRoomId(String roomName) throws SQLException {
        RoomRecord room = getRoomByName(roomName);
        return room != null ? room.id() : null;
    }

    public List<String> getAllRooms() throws SQLException {
        String sql = "SELECT name FROM rooms ORDER BY name ASC";
        List<String> roomNames = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                roomNames.add(rs.getString("name"));
            }
        }
        return roomNames;
    }

    /**
     * Persists a chat message row into the messages table.
     */
    public long saveMessage(Integer roomId, int senderId, Integer recipientId, String body) throws SQLException {
        if ((roomId == null && recipientId == null) || (roomId != null && recipientId != null)) {
            throw new IllegalArgumentException("Exactly one of roomId or recipientId must be specified per message row");
        }
        String sql = "INSERT INTO messages (room_id, sender_id, recipient_id, body) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (roomId != null) {
                stmt.setInt(1, roomId);
            } else {
                stmt.setNull(1, java.sql.Types.INTEGER);
            }
            stmt.setInt(2, senderId);
            if (recipientId != null) {
                stmt.setInt(3, recipientId);
            } else {
                stmt.setNull(3, java.sql.Types.INTEGER);
            }
            stmt.setString(4, body);
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return -1;
    }

    /**
     * Queries the most recent messages for a room (ordered chronologically).
     */
    public List<Message> getRecentRoomMessages(String roomName, int limit) throws SQLException {
        String sql = """
            SELECT m.body, m.sent_at, u.username AS sender_name
            FROM messages m
            JOIN rooms r ON m.room_id = r.id
            JOIN users u ON m.sender_id = u.id
            WHERE r.name = ?
            ORDER BY m.sent_at DESC
            LIMIT ?
        """;
        List<Message> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, roomName);
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String body = rs.getString("body");
                    Timestamp ts = rs.getTimestamp("sent_at");
                    String sender = rs.getString("sender_name");
                    long epochMillis = ts != null ? ts.getTime() : System.currentTimeMillis();
                    list.add(new Message(Protocol.MSG_ROOM_MSG, sender, null, roomName, body, epochMillis));
                }
            }
        }
        Collections.reverse(list);
        return list;
    }

    /**
     * Records a security/system event into the audit_log table.
     */
    public void logAuditEvent(Integer userId, String event, String detail) throws SQLException {
        String sql = "INSERT INTO audit_log (user_id, event, detail) VALUES (?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (userId != null) {
                stmt.setInt(1, userId);
            } else {
                stmt.setNull(1, java.sql.Types.INTEGER);
            }
            stmt.setString(2, event);
            stmt.setString(3, detail);
            stmt.executeUpdate();
        }
    }
}
