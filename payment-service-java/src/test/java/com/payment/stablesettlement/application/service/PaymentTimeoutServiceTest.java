package com.payment.stablesettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.stablesettlement.domain.hold.BalanceHold;
import com.payment.stablesettlement.domain.hold.BalanceHoldRepository;
import com.payment.stablesettlement.domain.hold.HoldStatus;
import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentTimeoutServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private BalanceHoldRepository balanceHoldRepository;

    @Mock
    private UserBalanceRepository userBalanceRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private PaymentTimeoutService paymentTimeoutService;

    private UserBalance senderBalance;
    private BalanceHold hold;

    @BeforeEach
    void setUp() {
        paymentTimeoutService = new PaymentTimeoutService(
            paymentRepository, balanceHoldRepository, userBalanceRepository,
            new PaymentOutboxRecorder(outboxEventRepository, new ObjectMapper(), FIXED_CLOCK),
            FIXED_CLOCK);

        senderBalance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        senderBalance.hold(new BigDecimal("300.00"));
        hold = BalanceHold.create("PAY-001", 1L, new BigDecimal("300.00"), "USDT", FIXED_CLOCK);
    }

    private Payment pendingPayment() {
        Payment payment = Payment.builder()
            .paymentReference("PAY-001")
            .senderId(1L)
            .receiverId(2L)
            .amount(new BigDecimal("300.00"))
            .currency("USDT")
            .clock(FIXED_CLOCK)
            .build();
        payment.markPgRequested("PGTX-001", FIXED_CLOCK);
        return payment;
    }

    @Test
    @DisplayName("PG 무응답 결제는 TIMEOUT으로 확정되고 가예약이 해제된다")
    void markTimedOut() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(payment));
        when(balanceHoldRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(hold));
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));

        paymentTimeoutService.markTimedOut("PAY-001", "No PG response within 300s");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.TIMEOUT);
        assertThat(payment.getFailureReason()).isEqualTo("No PG response within 300s");
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(senderBalance.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(senderBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PAYMENT_TIMEOUT");
    }

    @Test
    @DisplayName("이미 확정된 결제는 타임아웃 처리하지 않는다")
    void skipAlreadyFinalizedPayment() {
        Payment payment = pendingPayment();
        payment.complete(FIXED_CLOCK);
        when(paymentRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(payment));

        paymentTimeoutService.markTimedOut("PAY-001", "No PG response within 300s");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(balanceHoldRepository, never()).findByPaymentReference(any());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }
}
