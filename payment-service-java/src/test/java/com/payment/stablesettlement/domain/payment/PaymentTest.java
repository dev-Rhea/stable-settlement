package com.payment.stablesettlement.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("Payment Builder로 생성하면 PENDING 상태이다")
    void createPayment() {
        Payment payment = Payment.builder()
                .paymentReference("PAY-001")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .clock(FIXED_CLOCK)
                .build();

        assertThat(payment.getPaymentReference()).isEqualTo("PAY-001");
        assertThat(payment.getSenderId()).isEqualTo(1L);
        assertThat(payment.getReceiverId()).isEqualTo(2L);
        assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(payment.getCurrency()).isEqualTo("USDT");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getCreatedAt()).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 0, 0));
    }

    @Test
    @DisplayName("PENDING 상태에서 complete()하면 COMPLETED가 된다")
    void completePayment() {
        Payment payment = createPendingPayment();

        payment.complete(FIXED_CLOCK);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getUpdatedAt()).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 0, 0));
    }

    @Test
    @DisplayName("PENDING 상태에서 fail()하면 FAILED가 된다")
    void failPayment() {
        Payment payment = createPendingPayment();

        payment.fail("Insufficient balance", FIXED_CLOCK);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Insufficient balance");
        assertThat(payment.getUpdatedAt()).isEqualTo(LocalDateTime.of(2025, 1, 15, 10, 0, 0));
    }

    @Test
    @DisplayName("COMPLETED 상태에서 complete()하면 예외가 발생한다")
    void cannotCompleteCompletedPayment() {
        Payment payment = createPendingPayment();
        payment.complete(FIXED_CLOCK);

        assertThatThrownBy(() -> payment.complete(FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("COMPLETED 상태에서 fail()하면 예외가 발생한다")
    void cannotFailCompletedPayment() {
        Payment payment = createPendingPayment();
        payment.complete(FIXED_CLOCK);

        assertThatThrownBy(() -> payment.fail("reason", FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("FAILED 상태에서 complete()하면 예외가 발생한다")
    void cannotCompleteFailedPayment() {
        Payment payment = createPendingPayment();
        payment.fail("reason", FIXED_CLOCK);

        assertThatThrownBy(() -> payment.complete(FIXED_CLOCK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
    }

    private Payment createPendingPayment() {
        return Payment.builder()
                .paymentReference("PAY-001")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .clock(FIXED_CLOCK)
                .build();
    }
}
