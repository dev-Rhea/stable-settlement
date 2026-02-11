package com.payment.stablesettlement.domain.signing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "signing_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SigningKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedPrivateKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedDataKey;

    @Column(nullable = false)
    private String kmsKeyId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(nullable = false)
    private String algorithm;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public SigningKey(Long userId, String encryptedPrivateKey, String encryptedDataKey,
                      String kmsKeyId, String publicKey, String algorithm, Clock clock) {
        this.userId = userId;
        this.encryptedPrivateKey = encryptedPrivateKey;
        this.encryptedDataKey = encryptedDataKey;
        this.kmsKeyId = kmsKeyId;
        this.publicKey = publicKey;
        this.algorithm = algorithm;
        this.createdAt = LocalDateTime.now(clock);
    }

    @Override
    public String toString() {
        return "SigningKey{userId=" + userId
                + ", algorithm=" + algorithm
                + ", kmsKeyId=" + kmsKeyId
                + ", encryptedPrivateKey=[MASKED]"
                + ", encryptedDataKey=[MASKED]"
                + "}";
    }
}
