package darts.server;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Utility tool to promote a registered user to an administrator in the database.
 * Usage: java -cp "out;lib/*" darts.server.MakeAdmin <username>
 */
public class MakeAdmin {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -cp \"out;lib/*\" darts.server.MakeAdmin <username>");
            System.exit(1);
        }
        String username = args[0];
        Database db = new Database();
        try (Connection conn = db.getConnection()) {
            // Check if user exists
            var user = db.getUser(username);
            if (user == null) {
                System.out.println("[ERROR] User '" + username + "' does not exist in the database. Please register the user first.");
                System.exit(1);
            }
            
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE users SET is_admin = TRUE WHERE username = ?")) {
                stmt.setString(1, username);
                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    System.out.println("[SUCCESS] User '" + username + "' has been successfully promoted to Administrator.");
                } else {
                    System.out.println("[ERROR] Failed to update user status.");
                }
            }
        } catch (Exception e) {
            System.err.println("Database error: " + e.getMessage());
        }
    }
}
