package com.payment.stablesettlement.infrastructure.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-that-is-at-least-256-bits-long-for-hmac-sha";
    private static final long EXPIRATION_MS = 3600000;
    private static final Instant NOW = Instant.parse("2025-01-15T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET, EXPIRATION_MS, FIXED_CLOCK);
    }

    @Test
    @DisplayName("토큰 생성 - 유효한 JWT 토큰 반환")
    void generateToken() {
        String token = jwtTokenProvider.generateToken(1L);

        assertThat(token).isNotNull();
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("토큰에서 userId 추출")
    void getUserIdFromToken() {
        String token = jwtTokenProvider.generateToken(42L);

        Long userId = jwtTokenProvider.getUserIdFromToken(token);

        assertThat(userId).isEqualTo(42L);
    }

    @Test
    @DisplayName("유효한 토큰 검증 - true 반환")
    void validateValidToken() {
        String token = jwtTokenProvider.generateToken(1L);

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰 검증 - false 반환")
    void validateExpiredToken() {
        Clock pastClock = Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneId.of("UTC"));
        JwtTokenProvider pastProvider = new JwtTokenProvider(SECRET, EXPIRATION_MS, pastClock);
        String token = pastProvider.generateToken(1L);

        // NOW 시점에서 검증 - 토큰은 pastClock 기준 1시간 후 만료, NOW는 2시간 후이므로 만료됨
        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("위변조된 토큰 검증 - false 반환")
    void validateTamperedToken() {
        String token = jwtTokenProvider.generateToken(1L);
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("다른 secret으로 서명된 토큰 검증 - false 반환")
    void validateTokenWithDifferentSecret() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "another-secret-key-that-is-also-at-least-256-bits-long-for-hmac".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("1")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusMillis(EXPIRATION_MS)))
                .signWith(otherKey)
                .compact();

        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }
}
