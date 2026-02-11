package com.payment.stablesettlement.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public final class TestJwtHelper {

    private static final String TEST_SECRET =
            "test-secret-key-for-jwt-that-is-at-least-256-bits-long-for-hmac-sha";
    private static final SecretKey SECRET_KEY =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    private TestJwtHelper() {
    }

    public static String generateToken(Long userId) {
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(SECRET_KEY)
                .compact();
    }

    public static String bearerToken(Long userId) {
        return "Bearer " + generateToken(userId);
    }
}
