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

    @Test
    @DisplayName("가예약(hold)하면 heldAmount가 증가하고 가용잔액이 줄어든다")
    void holdAmount() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");

        balance.hold(new BigDecimal("300.00"));

        assertThat(balance.getHeldAmount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(balance.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    @DisplayName("가용잔액을 초과해서 hold하면 InsufficientBalanceException이 발생한다")
    void holdExceedsAvailableBalance() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        balance.hold(new BigDecimal("800.00"));

        assertThatThrownBy(() -> balance.hold(new BigDecimal("300.00")))
            .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    @DisplayName("0 이하 금액 hold 시 IllegalArgumentException이 발생한다")
    void holdNonPositiveAmount() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");

        assertThatThrownBy(() -> balance.hold(BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("releaseHold하면 heldAmount만 줄고 balance는 그대로다")
    void releaseHold() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        balance.hold(new BigDecimal("300.00"));

        balance.releaseHold(new BigDecimal("300.00"));

        assertThat(balance.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(balance.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("hold된 금액보다 많이 releaseHold하면 IllegalStateException이 발생한다")
    void releaseHoldMoreThanHeld() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        balance.hold(new BigDecimal("100.00"));

        assertThatThrownBy(() -> balance.releaseHold(new BigDecimal("200.00")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("captureHold하면 heldAmount와 balance가 함께 줄어든다")
    void captureHold() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        balance.hold(new BigDecimal("300.00"));

        balance.captureHold(new BigDecimal("300.00"));

        assertThat(balance.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(balance.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
    }

    @Test
    @DisplayName("hold된 금액보다 많이 captureHold하면 IllegalStateException이 발생한다")
    void captureHoldMoreThanHeld() {
        UserBalance balance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        balance.hold(new BigDecimal("100.00"));

        assertThatThrownBy(() -> balance.captureHold(new BigDecimal("200.00")))
            .isInstanceOf(IllegalStateException.class);
    }
}
