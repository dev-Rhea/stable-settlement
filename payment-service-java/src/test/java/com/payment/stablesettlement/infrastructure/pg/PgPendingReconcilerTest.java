package com.payment.stablesettlement.infrastructure.pg;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.stablesettlement.application.service.PaymentTimeoutService;
import com.payment.stablesettlement.application.service.PgCallbackService;
import com.payment.stablesettlement.application.service.PgClient;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.interfaces.dto.pg.PgCallbackRequest;
import com.payment.stablesettlement.interfaces.dto.pg.PgResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class PgPendingReconcilerTest {

    private static final long INQUIRY_AFTER_SECONDS = 30;
    private static final long GIVE_UP_AFTER_SECONDS = 300;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PgClient pgClient;

    @Mock
    private PgCallbackService pgCallbackService;

    @Mock
    private PaymentTimeoutService paymentTimeoutService;

    @Mock
    private RedissonClient redissonClient;

    private Clock clock;
    private PgPendingReconciler reconciler;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2025-01-15T10:10:00Z"), ZoneId.of("UTC"));
        reconciler = new PgPendingReconciler(paymentRepository, pgClient, pgCallbackService,
            paymentTimeoutService, redissonClient, clock,
            INQUIRY_AFTER_SECONDS, GIVE_UP_AFTER_SECONDS);
    }

    /**
     * @param pgRequestedAtIso PG 승인을 요청한 시각
     */
    private Payment stalePayment(String pgRequestedAtIso) {
        Clock requestClock = Clock.fixed(Instant.parse(pgRequestedAtIso), ZoneId.of("UTC"));
        Payment payment = Payment.builder()
            .paymentReference("PAY-001")
            .senderId(1L)
            .receiverId(2L)
            .amount(new BigDecimal("300.00"))
            .currency("USDT")
            .clock(requestClock)
            .build();
        payment.markPgRequested("PGTX-001", requestClock);
        return payment;
    }

    private void givenStalePayments(Payment... payments) {
        when(paymentRepository.findTop100ByStatusAndPgRequestedAtBeforeOrderByPgRequestedAtAsc(
            eq(PaymentStatus.PENDING), any(LocalDateTime.class)))
            .thenReturn(List.of(payments));
    }

    @Test
    @DisplayName("재조회로 승인 결과가 확인되면 콜백과 동일한 경로로 확정한다")
    void recoversLostApprovalCallback() {
        givenStalePayments(stalePayment("2025-01-15T10:09:00Z"));
        when(pgClient.queryResult("PGTX-001")).thenReturn(Optional.of(PgResult.APPROVED));

        reconciler.reconcile();

        ArgumentCaptor<PgCallbackRequest> captor =
            ArgumentCaptor.forClass(PgCallbackRequest.class);
        verify(pgCallbackService).handleCallback(captor.capture());
        Assertions.assertThat(captor.getValue().pgTransactionId()).isEqualTo("PGTX-001");
        Assertions.assertThat(captor.getValue().result()).isEqualTo(PgResult.APPROVED);
        verify(paymentTimeoutService, never()).markTimedOut(anyString(), anyString());
    }

    @Test
    @DisplayName("거절 결과를 회수할 때는 사유를 남긴다")
    void recoversLostRejectionCallback() {
        givenStalePayments(stalePayment("2025-01-15T10:09:00Z"));
        when(pgClient.queryResult("PGTX-001")).thenReturn(Optional.of(PgResult.REJECTED));

        reconciler.reconcile();

        ArgumentCaptor<PgCallbackRequest> captor =
            ArgumentCaptor.forClass(PgCallbackRequest.class);
        verify(pgCallbackService).handleCallback(captor.capture());
        Assertions.assertThat(captor.getValue().result()).isEqualTo(PgResult.REJECTED);
        Assertions.assertThat(captor.getValue().reason()).isNotBlank();
    }

    @Test
    @DisplayName("아직 미결정이고 포기 기준에 못 미치면 그대로 둔다")
    void leavesUndecidedPaymentAlone() {
        givenStalePayments(stalePayment("2025-01-15T10:09:00Z"));
        when(pgClient.queryResult("PGTX-001")).thenReturn(Optional.empty());

        reconciler.reconcile();

        verify(pgCallbackService, never()).handleCallback(any(PgCallbackRequest.class));
        verify(paymentTimeoutService, never()).markTimedOut(anyString(), anyString());
    }

    @Test
    @DisplayName("포기 기준을 넘긴 미결정 결제는 TIMEOUT으로 넘긴다")
    void timesOutPaymentPastGiveUpThreshold() {
        givenStalePayments(stalePayment("2025-01-15T10:00:00Z"));
        when(pgClient.queryResult("PGTX-001")).thenReturn(Optional.empty());

        reconciler.reconcile();

        verify(paymentTimeoutService).markTimedOut(eq("PAY-001"), anyString());
        verify(pgCallbackService, never()).handleCallback(any(PgCallbackRequest.class));
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 건은 계속 처리한다")
    void continuesAfterFailure() {
        Payment first = stalePayment("2025-01-15T10:09:00Z");
        Payment second = stalePayment("2025-01-15T10:09:30Z");
        givenStalePayments(first, second);
        when(pgClient.queryResult("PGTX-001"))
            .thenThrow(new IllegalStateException("boom"))
            .thenReturn(Optional.of(PgResult.APPROVED));

        reconciler.reconcile();

        verify(pgCallbackService).handleCallback(any(PgCallbackRequest.class));
    }
}
