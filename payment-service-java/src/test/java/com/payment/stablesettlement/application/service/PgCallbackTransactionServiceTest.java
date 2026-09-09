package com.payment.stablesettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.payment.stablesettlement.interfaces.dto.pg.PgCallbackRequest;
import com.payment.stablesettlement.interfaces.dto.pg.PgResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
class PgCallbackTransactionServiceTest {

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

    @Mock
    private TransactionSigner transactionSigner;

    private MeterRegistry meterRegistry;

    private PgCallbackTransactionService pgCallbackTransactionService;

    private UserBalance senderBalance;
    private UserBalance receiverBalance;
    private BalanceHold hold;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        pgCallbackTransactionService = new PgCallbackTransactionService(
            paymentRepository, balanceHoldRepository, userBalanceRepository,
            outboxEventRepository, new ObjectMapper(), FIXED_CLOCK, meterRegistry,
            transactionSigner);

        senderBalance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        senderBalance.hold(new BigDecimal("300.00"));
        receiverBalance = new UserBalance(2L, new BigDecimal("500.00"), "USDT");
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
    @DisplayName("승인 콜백은 가예약을 확정 차감하고 수신자 잔액을 늘린다")
    void approvedCallbackCapturesHold() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByPgTransactionId("PGTX-001")).thenReturn(Optional.of(payment));
        when(balanceHoldRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(hold));
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));
        when(transactionSigner.signTransaction(any(Payment.class))).thenReturn("sig-abc");

        pgCallbackTransactionService.finalizeCallback(
            new PgCallbackRequest("PGTX-001", PgResult.APPROVED, null));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getTransactionSignature()).isEqualTo("sig-abc");
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(senderBalance.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(senderBalance.getBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(receiverBalance.getBalance()).isEqualByComparingTo(new BigDecimal("800.00"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getAggregateType()).isEqualTo("Payment");
        assertThat(event.getAggregateId()).isEqualTo("PAY-001");
        assertThat(event.getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(event.getPayload()).contains("sig-abc");
        assertThat(event.isPublished()).isFalse();
    }

    @Test
    @DisplayName("거절 콜백은 가예약만 해제하고 잔액은 원상 복구한다")
    void rejectedCallbackReleasesHold() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByPgTransactionId("PGTX-001")).thenReturn(Optional.of(payment));
        when(balanceHoldRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(hold));
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));

        pgCallbackTransactionService.finalizeCallback(
            new PgCallbackRequest("PGTX-001", PgResult.REJECTED, "declined"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("declined");
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(senderBalance.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(senderBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        verify(transactionSigner, never()).signTransaction(any(Payment.class));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    @DisplayName("종단 상태 결제에 대한 중복 콜백은 아무것도 바꾸지 않는다")
    void duplicateCallbackIsIgnored() {
        Payment payment = pendingPayment();
        payment.complete(FIXED_CLOCK);
        when(paymentRepository.findByPgTransactionId("PGTX-001")).thenReturn(Optional.of(payment));

        pgCallbackTransactionService.finalizeCallback(
            new PgCallbackRequest("PGTX-001", PgResult.APPROVED, null));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verifyNoInteractions(balanceHoldRepository, userBalanceRepository,
            outboxEventRepository, transactionSigner);
        verify(paymentRepository, never()).save(any(Payment.class));
        assertThat(meterRegistry.counter("payment.pg_callback.processed", "status", "duplicate")
            .count()).isEqualTo(1.0);
    }
}
