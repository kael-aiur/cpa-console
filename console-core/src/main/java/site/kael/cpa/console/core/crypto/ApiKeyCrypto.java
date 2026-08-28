package site.kael.cpa.console.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class ApiKeyCrypto {
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final SecretKey encryptionKey;
    private final byte[] hashSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyCrypto(String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalArgumentException("CPA_CONSOLE_API_KEY_ENCRYPTION_KEY must be configured");
        }
        byte[] keyBytes = Base64.getDecoder().decode(encryptionKey.trim());
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalArgumentException("CPA_CONSOLE_API_KEY_ENCRYPTION_KEY must be a base64 AES key");
        }
        this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
        try {
            this.hashSecret = MessageDigest.getInstance("SHA-256").digest(keyBytes);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String hash(String apiKey) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash API key", exception);
        }
    }

    public String encrypt(String value) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt API key", exception);
        }
    }

    public String decrypt(String value) {
        try {
            byte[] payload = Base64.getDecoder().decode(value);
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt API key", exception);
        }
    }
}
