package darts.server;

import darts.common.CryptoUtils;
import darts.common.Protocol;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles user registration, password verification, failed-login rate limiting,
 * and security audit logging. Delegates password hashing to CryptoUtils.
 */
public class AuthManager {

    public static final int MAX_LOGIN_FAILURES = 5;
    public static final long FAILURE_WINDOW_MS = 60_000L;
    public static final long BLOCK_DURATION_MS = 60_000L;
    public static final int MIN_PASSWORD_LEN = 6;

    public static final String AUDIT_LOGIN_FAIL = "LOGIN_FAIL";
    public static final String AUDIT_LOGIN_OK = "LOGIN_OK";
    public static final String AUDIT_REGISTER = "REGISTER";

    private final Database db;
    private final Map<String, RateLimitEntry> rateLimits = new ConcurrentHashMap<>();

    public AuthManager(Database db) {
        this.db = db;
    }

    /**
     * Registers a new user after validating username/password bounds.
     */
    public RegisterResult register(String username, String password) {
        String validationError = validateCredentials(username, password);
        if (validationError != null) {
            return RegisterResult.fail(validationError);
        }

        try {
            if (db.getUser(username) != null) {
                return RegisterResult.fail("Username already exists");
            }

            String salt = CryptoUtils.generateSalt();
            String hash = CryptoUtils.hashPassword(password, salt);
            Database.UserRecord user = db.createUser(username, hash, salt, false);

            db.logAuditEvent(user.id(), AUDIT_REGISTER, "username=" + username);
            return RegisterResult.ok(user);
        } catch (SQLException e) {
            return RegisterResult.fail("Database error during registration: " + e.getMessage());
        }
    }

    /**
     * Authenticates a user against stored PBKDF2 hash, enforcing per-username rate limits on failures.
     */
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank()) {
            return LoginResult.fail("Username required");
        }
        if (username.length() > Protocol.MAX_USERNAME_LEN) {
            return LoginResult.fail("Username exceeds maximum length");
        }

        Optional<String> blockReason = checkRateLimit(username);
        if (blockReason.isPresent()) {
            return LoginResult.fail(blockReason.get());
        }

        try {
            Database.UserRecord user = db.getUser(username);
            if (user == null) {
                recordLoginFailure(username, null);
                return LoginResult.fail("Invalid username or password");
            }

            if (!CryptoUtils.verifyPassword(password, user.salt(), user.passwordHash())) {
                recordLoginFailure(username, user.id());
                return LoginResult.fail("Invalid username or password");
            }

            clearRateLimit(username);
            db.updateLastLogin(user.id());
            db.logAuditEvent(user.id(), AUDIT_LOGIN_OK, "Successful login");
            return LoginResult.ok(user);
        } catch (SQLException e) {
            return LoginResult.fail("Database error during login: " + e.getMessage());
        }
    }

    public boolean isAdmin(int userId) {
        // Can be checked directly or from user record
        return false;
    }

    private static String validateCredentials(String username, String password) {
        if (username == null || username.isBlank()) {
            return "Username required";
        }
        if (username.length() > Protocol.MAX_USERNAME_LEN) {
            return "Username exceeds maximum length of " + Protocol.MAX_USERNAME_LEN;
        }
        if (password == null || password.length() < MIN_PASSWORD_LEN) {
            return "Password must be at least " + MIN_PASSWORD_LEN + " characters";
        }
        if (password.length() > Protocol.MAX_BODY_LEN) {
            return "Password exceeds maximum length of " + Protocol.MAX_BODY_LEN;
        }
        return null;
    }

    private Optional<String> checkRateLimit(String username) {
        RateLimitEntry entry = rateLimits.get(username);
        if (entry == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (entry.blockedUntil > 0 && now < entry.blockedUntil) {
            return Optional.of("Too many failed login attempts; try again later");
        }
        if (entry.blockedUntil > 0 && now >= entry.blockedUntil) {
            rateLimits.remove(username);
        }
        return Optional.empty();
    }

    private void recordLoginFailure(String username, Integer userId) {
        long now = System.currentTimeMillis();
        rateLimits.compute(username, (key, existing) -> {
            RateLimitEntry entry = existing != null ? existing : new RateLimitEntry();
            if (entry.firstFailureAt > 0 && now - entry.firstFailureAt > FAILURE_WINDOW_MS) {
                entry.failureCount = 0;
                entry.firstFailureAt = 0;
                entry.blockedUntil = 0;
            }
            if (entry.failureCount == 0) {
                entry.firstFailureAt = now;
            }
            entry.failureCount++;
            if (entry.failureCount >= MAX_LOGIN_FAILURES
                    && now - entry.firstFailureAt <= FAILURE_WINDOW_MS) {
                entry.blockedUntil = now + BLOCK_DURATION_MS;
            }
            return entry;
        });

        try {
            db.logAuditEvent(userId, AUDIT_LOGIN_FAIL, "username=" + username);
        } catch (SQLException ignored) {
        }
    }

    private void clearRateLimit(String username) {
        rateLimits.remove(username);
    }

    private static final class RateLimitEntry {
        int failureCount;
        long firstFailureAt;
        long blockedUntil;
    }

    public record LoginResult(boolean isSuccess, String reason, Database.UserRecord user) {
        public static LoginResult ok(Database.UserRecord user) {
            return new LoginResult(true, null, user);
        }

        public static LoginResult fail(String reason) {
            return new LoginResult(false, reason, null);
        }
    }

    public record RegisterResult(boolean isSuccess, String reason, Database.UserRecord user) {
        public static RegisterResult ok(Database.UserRecord user) {
            return new RegisterResult(true, null, user);
        }

        public static RegisterResult fail(String reason) {
            return new RegisterResult(false, reason, null);
        }
    }
}
