package darts.server;

import java.util.Optional;

/**
 * Persistence contract for user accounts and audit events.
 * {@link Database} (Track C) must implement this interface.
 */
public interface UserRepository {

    Optional<UserRecord> getUserByUsername(String username);

    Optional<UserRecord> getUserById(int userId);

    boolean createUser(String username, String passwordHash, String salt);

    void updateLastLogin(int userId);

    void logAuditEvent(Integer userId, String event, String detail);
}
