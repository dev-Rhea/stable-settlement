package com.payment.stablesettlement.domain.user;

import com.payment.stablesettlement.application.exception.InsufficientBalanceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "user_balance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal heldAmount;

    @Version
    private Long version;

    public UserBalance(Long userId, BigDecimal balance, String currency) {
        this.userId = userId;
        this.balance = balance;
        this.currency = currency;
        this.heldAmount = BigDecimal.ZERO;
    }

    public void deduct(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deduct amount must be positive");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(this.userId, this.balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        this.balance = this.balance.add(amount);
    }

    public BigDecimal getAvailableBalance() {
        return this.balance.subtract(this.heldAmount);
    }

    public void hold(BigDecimal amount) {
        if(amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Hold amount must be positive");
        }

        BigDecimal available = getAvailableBalance();
        if(available.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(this.userId, available, amount);
        }
        this.heldAmount = this.heldAmount.add(amount);
    }

    public void releaseHold(BigDecimal amount) {
        if(amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Release amount must be positive");
        }

        if(this.heldAmount.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot release more than held");
        }

        this.heldAmount = this.heldAmount.subtract(amount);
    }

    public void captureHold(BigDecimal amount) {
        if(amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Capture amount must be positive");
        }

        if(this.heldAmount.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot capture more than held");
        }

        this.heldAmount = this.heldAmount.subtract(amount);
        this.balance = this.balance.subtract(amount);
    }
}
