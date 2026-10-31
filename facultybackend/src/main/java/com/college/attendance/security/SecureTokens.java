package com.college.attendance.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cryptographically secure token utilities.
 *
 * Activation / password-reset tokens are generated from a CSPRNG (SecureRandom)
 * and only their SHA-256 hash is persisted, so a database leak cannot be replayed
 * against the activation endpoint. Tokens are single-use: the stored hash is
 * cleared as soon as it is consumed.
 */
public final class SecureTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256 bits of entropy

    private SecureTokens() {
    }

    /** Generate an unpredictable, URL-safe activation/reset token. */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex digest of a token — the only form ever stored in the database. */
    public static String sha256(String token) {
        if (token == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
