package com.payment.stablesettlement.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String paymentReference;

    @Column(nullable = false)
    private Long senderId;

    @Column(nullable = false)
    private Long receiverId;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String failureReason;

    @Column(columnDefinition = "TEXT")
    private String transactionSignature;

    @Column(unique = true)
    private String pgTransactionId;

    private LocalDateTime pgRequestedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Builder
    public Payment(String paymentReference, Long senderId, Long receiverId,
                   BigDecimal amount, String currency, Clock clock) {
        this.paymentReference = paymentReference;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
        this.createdAt = LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());
    }

    public void markPgRequested(String pgTransactionId, Clock clock) {
        if(this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Cannot mark PG requested for payment in status: " + this.status);
        }

        if(this.pgTransactionId != null) {
            throw new IllegalStateException("PG transaction already requested");
        }

        if(pgTransactionId == null || pgTransactionId.isBlank()) {
            throw new IllegalArgumentException("pgTransactionId must not be blank");
        }

        this.pgTransactionId = pgTransactionId;
        this.pgRequestedAt = LocalDateTime.now(clock);
    }

    public void complete(Clock clock) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot complete payment in status: " + this.status);
        }
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = LocalDateTime.now(clock);
    }

    public void attachSignature(String transactionSignature) {
        if (this.transactionSignature != null) {
            throw new IllegalStateException("Transaction signature already attached");
        }
        if (transactionSignature == null || transactionSignature.isBlank()) {
            throw new IllegalArgumentException("Transaction signature must not be blank");
        }
        this.transactionSignature = transactionSignature;
    }

    public void fail(String reason, Clock clock) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot fail payment in status: " + this.status);
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = LocalDateTime.now(clock);
    }
}
