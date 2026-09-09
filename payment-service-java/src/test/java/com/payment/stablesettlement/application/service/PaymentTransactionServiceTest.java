package com.payment.stablesettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.stablesettlement.application.exception.DuplicatePaymentException;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    @Mock
    private PaymentHoldService paymentHoldService;

    @Mock
    private PgClient pgClient;

    private MeterRegistry meterRegistry;

    private PaymentTransactionService paymentTransactionService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        paymentTransactionService = new PaymentTransactionService(
            paymentHoldService, pgClient, meterRegistry);
    }

    private PaymentRequest request() {
        return new PaymentRequest("PAY-001", 1L, 2L, new BigDecimal("300.00"), "USDT");
    }

    private Payment pendingPayment() {
        return Payment.builder()
            .paymentReference("PAY-001")
            .senderId(1L)
            .receiverId(2L)
            .amount(new BigDecimal("300.00"))
            .currency("USDT")
            .clock(FIXED_CLOCK)
            .build();
    }

    @Test
    @DisplayName("가예약 생성 후 PG 승인 요청까지 마친 PENDING 결제를 반환한다")
    void executePaymentSuccess() {
        PaymentRequest request = request();
        Payment held = pendingPayment();
        Payment attached = pendingPayment();
        attached.markPgRequested("PGTX-001", FIXED_CLOCK);

        when(paymentHoldService.createHold(request)).thenReturn(held);
        when(pgClient.requestApproval("PAY-001", new BigDecimal("300.00"), "USDT"))
            .thenReturn("PGTX-001");
        when(paymentHoldService.attachPgTransaction("PAY-001", "PGTX-001")).thenReturn(attached);

        Payment result = paymentTransactionService.executePayment(request);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.getPgTransactionId()).isEqualTo("PGTX-001");
        assertThat(result.getPgRequestedAt()).isNotNull();
        assertThat(meterRegistry.counter("payment.processed", "status", "success").count())
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("가예약 생성이 실패하면 PG 승인 요청을 보내지 않는다")
    void executePaymentHoldFailure() {
        PaymentRequest request = request();

        when(paymentHoldService.createHold(request))
            .thenThrow(new DuplicatePaymentException("PAY-001"));

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
            .isInstanceOf(DuplicatePaymentException.class);

        verify(pgClient, never()).requestApproval(anyString(), any(), anyString());
        verify(paymentHoldService, never()).releaseOnInitiationFailure(anyString(), anyString());
        assertThat(meterRegistry.counter("payment.processed", "status", "failure").count())
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("PG 승인 요청이 실패하면 가예약을 해제하고 예외를 전파한다")
    void executePaymentPgFailureReleasesHold() {
        PaymentRequest request = request();

        when(paymentHoldService.createHold(request)).thenReturn(pendingPayment());
        when(pgClient.requestApproval("PAY-001", new BigDecimal("300.00"), "USDT"))
            .thenThrow(new PaymentException("PG_REQUEST_FAILED", "Failed to reach Mock PG"));

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
            .isInstanceOf(PaymentException.class)
            .extracting(e -> ((PaymentException) e).getErrorCode())
            .isEqualTo("PG_REQUEST_FAILED");

        verify(paymentHoldService).releaseOnInitiationFailure("PAY-001", "Failed to reach Mock PG");
        verify(paymentHoldService, never()).attachPgTransaction(anyString(), anyString());
        assertThat(meterRegistry.counter("payment.processed", "status", "failure").count())
            .isEqualTo(1.0);
    }
}
