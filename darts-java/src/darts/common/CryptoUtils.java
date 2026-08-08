package darts.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.KeyFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Crypto and hashing helper utilities implementing PBKDF2-HMAC-SHA256 password hashing
 * and constant-time digest comparison according to security specifications.
 */
public final class CryptoUtils {

    public static final int SALT_BYTE_LENGTH = 16;
    public static final int PBKDF2_ITERATIONS = 100_000;
    public static final int KEY_LENGTH_BITS = 256;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtils() {
        // Prevent instantiation
    }

    /**
     * Generates a cryptographically secure random 16-byte salt as a hex string.
     */
    public static String generateSalt() {
        byte[] salt = new byte[SALT_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return bytesToHex(salt);
    }

    /**
     * Derives a PBKDF2-HMAC-SHA256 hash from password and hex-encoded salt.
     *
     * @param password plain text password
     * @param hexSalt 32-character hex salt
     * @return 64-character hex encoded hash string
     */
    public static String hashPassword(String password, String hexSalt) {
        if (password == null || hexSalt == null) {
            throw new IllegalArgumentException("Password and salt cannot be null");
        }
        byte[] saltBytes = hexToBytes(hexSalt);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error computing PBKDF2 password hash", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Verifies a plain text password against a hex salt and expected hash string in constant time.
     */
    public static boolean verifyPassword(String password, String hexSalt, String expectedHash) {
        if (password == null || hexSalt == null || expectedHash == null) {
            return false;
        }
        try {
            String computed = hashPassword(password, hexSalt);
            return constantTimeEquals(computed, expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Performs constant-time comparison of two hex-encoded hash strings to protect against timing attacks.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex string length must be even");
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static KeyPair generateRSAKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("RSA key generation failed", e);
        }
    }

    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode RSA Public Key", e);
        }
    }

    public static String encodePrivateKey(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PrivateKey decodePrivateKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(bytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode RSA Private Key", e);
        }
    }

    public static String encryptRSA(byte[] data, PublicKey publicKey) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] cipherBytes = cipher.doFinal(data);
            return Base64.getEncoder().encodeToString(cipherBytes);
        } catch (Exception e) {
            throw new RuntimeException("RSA encryption failed", e);
        }
    }

    public static byte[] decryptRSA(String ciphertextBase64, PrivateKey privateKey) {
        try {
            byte[] bytes = Base64.getDecoder().decode(ciphertextBase64);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(bytes);
        } catch (Exception e) {
            throw new RuntimeException("RSA decryption failed", e);
        }
    }

    public static byte[] generateAESKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            return kg.generateKey().getEncoded();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("AES key generation failed", e);
        }
    }

    public static byte[] generateIV() {
        byte[] iv = new byte[16];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    public static String encryptAES(String plaintext, byte[] aesKey, byte[] iv) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipherBytes);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed", e);
        }
    }

    public static String decryptAES(String ciphertextBase64, byte[] aesKey, byte[] iv) {
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(ciphertextBase64);
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed", e);
        }
    }
}
