package com.payment.stablesettlement.domain.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class BalanceHoldTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("create()로 생성하면 HELD 상태이다")
    void createHold() {
        BalanceHold hold = BalanceHold.create(
            "PAY-001", 1L, new BigDecimal("100.00"), "USDT", FIXED_CLOCK);

        assertThat(hold.getPaymentReference()).isEqualTo("PAY-001");
        assertThat(hold.getPayerId()).isEqualTo(1L);
        assertThat(hold.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(hold.getCurrency()).isEqualTo("USDT");
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.HELD);
        assertThat(hold.getCreatedAt()).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 0, 0));
    }

    @Test
    @DisplayName("HELD 상태에서 capture()하면 CAPTURED가 된다")
    void captureHold() {
        BalanceHold hold = createHeldHold();

        hold.capture(FIXED_CLOCK);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(hold.getUpdatedAt()).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 0, 0));
    }

    @Test
    @DisplayName("HELD 상태에서 release()하면 RELEASED가 된다")
    void releaseHold() {
        BalanceHold hold = createHeldHold();

        hold.release(FIXED_CLOCK);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(hold.getUpdatedAt()).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 0, 0));
    }

    @Test
    @DisplayName("CAPTURED 상태에서 capture()하면 예외가 발생한다")
    void cannotCaptureCapturedHold() {
        BalanceHold hold = createHeldHold();
        hold.capture(FIXED_CLOCK);

        assertThatThrownBy(() -> hold.capture(FIXED_CLOCK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAPTURED");
    }

    @Test
    @DisplayName("CAPTURED 상태에서 release()하면 예외가 발생한다")
    void cannotReleaseCapturedHold() {
        BalanceHold hold = createHeldHold();
        hold.capture(FIXED_CLOCK);

        assertThatThrownBy(() -> hold.release(FIXED_CLOCK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAPTURED");
    }

    @Test
    @DisplayName("RELEASED 상태에서 capture()하면 예외가 발생한다")
    void cannotCaptureReleasedHold() {
        BalanceHold hold = createHeldHold();
        hold.release(FIXED_CLOCK);

        assertThatThrownBy(() -> hold.capture(FIXED_CLOCK))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("RELEASED");
    }

    private BalanceHold createHeldHold() {
        return BalanceHold.create("PAY-001", 1L, new BigDecimal("100.00"), "USDT", FIXED_CLOCK);
    }
}
