package darts.common;

/**
 * Unit test for CryptoUtils PBKDF2 password hashing and constant time comparisons.
 */
public class CryptoTest {
    public static void main(String[] args) {
        System.out.println("Running CryptoUtils unit tests...");

        testSaltGeneration();
        testHashConsistency();
        testConstantTimeEquals();
        testRSAAndAESOperations();

        System.out.println("[SUCCESS] All CryptoUtils unit tests passed!");
    }

    private static void testSaltGeneration() {
        String salt1 = CryptoUtils.generateSalt();
        String salt2 = CryptoUtils.generateSalt();

        if (salt1.length() != 32 || salt2.length() != 32) {
            throw new RuntimeException("Salt hex length should be 32 chars (16 bytes)");
        }
        if (salt1.equals(salt2)) {
            throw new RuntimeException("Salt generation should yield unique salts");
        }
    }

    private static void testHashConsistency() {
        String password = "SecretPassword123!";
        String salt = CryptoUtils.generateSalt();

        String hash1 = CryptoUtils.hashPassword(password, salt);
        String hash2 = CryptoUtils.hashPassword(password, salt);

        if (!hash1.equals(hash2)) {
            throw new RuntimeException("Same password + salt must yield identical hash");
        }
        if (hash1.length() != 64) {
            throw new RuntimeException("Hash hex length should be 64 chars (256-bit)");
        }

        String wrongHash = CryptoUtils.hashPassword("WrongPassword", salt);
        if (hash1.equals(wrongHash)) {
            throw new RuntimeException("Different passwords must yield different hashes");
        }
    }

    private static void testConstantTimeEquals() {
        String h1 = "a1b2c3d4e5f6";
        String h2 = "a1b2c3d4e5f6";
        String h3 = "a1b2c3d4e5f7";

        if (!CryptoUtils.constantTimeEquals(h1, h2)) {
            throw new RuntimeException("Constant time comparison failed on matching strings");
        }
        if (CryptoUtils.constantTimeEquals(h1, h3)) {
            throw new RuntimeException("Constant time comparison failed on mismatching strings");
        }
    }

    private static void testRSAAndAESOperations() {
        // Test RSA key pair generation & encoding
        java.security.KeyPair kp = CryptoUtils.generateRSAKeyPair();
        String pubStr = CryptoUtils.encodePublicKey(kp.getPublic());
        String privStr = CryptoUtils.encodePrivateKey(kp.getPrivate());

        java.security.PublicKey decodedPub = CryptoUtils.decodePublicKey(pubStr);
        java.security.PrivateKey decodedPriv = CryptoUtils.decodePrivateKey(privStr);

        // Test RSA encrypt/decrypt
        byte[] aesKey = CryptoUtils.generateAESKey();
        String encKey = CryptoUtils.encryptRSA(aesKey, decodedPub);
        byte[] decKey = CryptoUtils.decryptRSA(encKey, decodedPriv);

        if (!java.util.Arrays.equals(aesKey, decKey)) {
            throw new RuntimeException("Decrypted AES key does not match original");
        }

        // Test AES encrypt/decrypt
        String secretMsg = "Super Secret Message!";
        byte[] iv = CryptoUtils.generateIV();
        String cipherText = CryptoUtils.encryptAES(secretMsg, aesKey, iv);
        String decrypted = CryptoUtils.decryptAES(cipherText, aesKey, iv);

        if (!secretMsg.equals(decrypted)) {
            throw new RuntimeException("Decrypted message does not match original: " + decrypted);
        }
    }
}
