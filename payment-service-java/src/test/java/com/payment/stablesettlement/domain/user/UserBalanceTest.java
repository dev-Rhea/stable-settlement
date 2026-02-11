package com.payment.stablesettlement.domain.user;

import com.payment.stablesettlement.application.exception.InsufficientBalanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserBalanceTest {

    @Test
    @DisplayName("잔액 차감이 정상 동작한다")
    void deductBalance() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");

        balance.deduct(new BigDecimal("300.00"));

        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    @DisplayName("잔액이 부족하면 InsufficientBalanceException이 발생한다")
    void deductInsufficientBalance() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("100.00"), "USDT");

        assertThatThrownBy(() -> balance.deduct(new BigDecimal("200.00")))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    @DisplayName("0 이하 금액 차감 시 IllegalArgumentException이 발생한다")
    void deductNonPositiveAmount() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");

        assertThatThrownBy(() -> balance.deduct(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> balance.deduct(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잔액 충전이 정상 동작한다")
    void creditBalance() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("500.00"), "USDT");

        balance.credit(new BigDecimal("200.00"));

        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    @DisplayName("0 이하 금액 충전 시 IllegalArgumentException이 발생한다")
    void creditNonPositiveAmount() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("500.00"), "USDT");

        assertThatThrownBy(() -> balance.credit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> balance.credit(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("잔액과 정확히 같은 금액 차감이 가능하다")
    void deductExactBalance() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("100.00"), "USDT");

        balance.deduct(new BigDecimal("100.00"));

        assertThat(balance.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
