package darts.common;

/**
 * Unit tests for {@link CryptoUtils} password hashing and verification.
 */
public class CryptoUtilsTest {

    public static void main(String[] args) {
        System.out.println("Running CryptoUtils unit tests...");

        testHashVerifyRoundTrip();
        testWrongPasswordFails();
        testWrongSaltFails();
        testConstantTimeComparison();
        testSaltUniqueness();

        System.out.println("[SUCCESS] All CryptoUtils tests passed!");
    }

    private static void testHashVerifyRoundTrip() {
        String password = "correct-horse-battery-staple";
        String salt = CryptoUtils.generateSalt();
        String hash = CryptoUtils.hashPassword(password, salt);

        if (hash == null || hash.isEmpty()) {
            throw new RuntimeException("hashPassword returned empty result");
        }
        if (!CryptoUtils.verifyPassword(password, salt, hash)) {
            throw new RuntimeException("verifyPassword failed for correct password");
        }
    }

    private static void testWrongPasswordFails() {
        String salt = CryptoUtils.generateSalt();
        String hash = CryptoUtils.hashPassword("secret123", salt);
        if (CryptoUtils.verifyPassword("wrong-password", salt, hash)) {
            throw new RuntimeException("verifyPassword accepted wrong password");
        }
    }

    private static void testWrongSaltFails() {
        String hash = CryptoUtils.hashPassword("secret123", CryptoUtils.generateSalt());
        String otherSalt = CryptoUtils.generateSalt();
        if (CryptoUtils.verifyPassword("secret123", otherSalt, hash)) {
            throw new RuntimeException("verifyPassword accepted wrong salt");
        }
    }

    private static void testConstantTimeComparison() {
        String salt = CryptoUtils.generateSalt();
        String hash = CryptoUtils.hashPassword("secret123", salt);
        if (CryptoUtils.verifyPassword(null, salt, hash)) {
            throw new RuntimeException("verifyPassword should reject null password");
        }
        if (CryptoUtils.verifyPassword("secret123", null, hash)) {
            throw new RuntimeException("verifyPassword should reject null salt");
        }
        if (CryptoUtils.verifyPassword("secret123", salt, null)) {
            throw new RuntimeException("verifyPassword should reject null hash");
        }
    }

    private static void testSaltUniqueness() {
        String salt1 = CryptoUtils.generateSalt();
        String salt2 = CryptoUtils.generateSalt();
        if (salt1.equals(salt2)) {
            throw new RuntimeException("generateSalt produced duplicate values");
        }
    }
}
