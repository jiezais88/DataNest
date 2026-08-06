package com.datanest.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密配置。
 * 使用 SHA-256 从配置密钥派生 256-bit 加密密钥；每次加密生成随机 96-bit IV。
 */
@Component
@ConfigurationProperties(prefix = "datanest.security.encryption")
public class EncryptionConfig {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int AES_KEY_LENGTH = 32;

    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        SecretKey secretKey = deriveSecretKey();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt data source password", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return encrypted;
        }
        SecretKey secretKey = deriveSecretKey();
        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            if (decoded.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted payload");
            }
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[decoded.length - GCM_IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(decoded, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt data source password", e);
        }
    }

    private SecretKey deriveSecretKey() {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Encryption key must not be empty");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[AES_KEY_LENGTH];
            System.arraycopy(hash, 0, result, 0, AES_KEY_LENGTH);
            return new SecretKeySpec(result, ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }
}
