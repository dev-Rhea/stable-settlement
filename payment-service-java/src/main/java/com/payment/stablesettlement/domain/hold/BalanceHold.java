package com.payment.stablesettlement.domain.hold;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Table(name = "balance_hold")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BalanceHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String paymentReference;

    @Column(nullable = false)
    private Long payerId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private BalanceHold(String paymentReference, Long payerId, BigDecimal amount, String currency, Clock clock) {
        this.paymentReference = paymentReference;
        this.payerId = payerId;
        this.amount = amount;
        this.currency = currency;
        this.status = HoldStatus.HELD;
        this.createdAt = LocalDateTime.now(clock);
    }

    public static BalanceHold create(String paymentReference, Long payerId, BigDecimal amount, String currency, Clock clock) {
        return new BalanceHold(paymentReference, payerId, amount, currency, clock);
    }

    public void capture(Clock clock) {
        if(this.status != HoldStatus.HELD) {
            throw new IllegalStateException("Cannot capture held in status: " + this.status);
        }

        this.status = HoldStatus.CAPTURED;
        this.updatedAt = LocalDateTime.now(clock);
    }

    public void release(Clock clock) {
        if (this.status != HoldStatus.HELD) {
            throw new IllegalStateException("Cannot released hold in status: " + this.status);
        }

        this.status = HoldStatus.RELEASED;
        this.updatedAt = LocalDateTime.now(clock);
    }
}
