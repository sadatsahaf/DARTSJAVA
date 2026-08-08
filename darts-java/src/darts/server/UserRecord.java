package darts.server;

/**
 * In-memory representation of a persisted user row from the {@code users} table.
 */
public record UserRecord(
        int id,
        String username,
        String passwordHash,
        String salt,
        boolean admin) {
}
