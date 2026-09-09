package com.payment.stablesettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.stablesettlement.application.exception.DuplicatePaymentException;
import com.payment.stablesettlement.application.exception.InsufficientBalanceException;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.hold.BalanceHold;
import com.payment.stablesettlement.domain.hold.BalanceHoldRepository;
import com.payment.stablesettlement.domain.hold.HoldStatus;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
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
class PaymentHoldServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private UserBalanceRepository userBalanceRepository;

    @Mock
    private BalanceHoldRepository balanceHoldRepository;

    private PaymentHoldService paymentHoldService;

    private UserBalance senderBalance;
    private UserBalance receiverBalance;

    @BeforeEach
    void setUp() {
        paymentHoldService = new PaymentHoldService(
            paymentRepository, userBalanceRepository, balanceHoldRepository, FIXED_CLOCK);
        senderBalance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        receiverBalance = new UserBalance(2L, new BigDecimal("500.00"), "USDT");
    }

    private PaymentRequest request(String amount, String currency) {
        return new PaymentRequest("PAY-001", 1L, 2L, new BigDecimal(amount), currency);
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
    @DisplayName("가예약 생성 시 잔액은 그대로이고 heldAmount만 늘어난다")
    void createHoldSuccess() {
        PaymentRequest request = request("300.00", "USDT");
        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));

        Payment payment = paymentHoldService.createHold(request);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getPgTransactionId()).isNull();
        assertThat(senderBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(senderBalance.getHeldAmount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(senderBalance.getAvailableBalance())
            .isEqualByComparingTo(new BigDecimal("700.00"));

        ArgumentCaptor<BalanceHold> captor = ArgumentCaptor.forClass(BalanceHold.class);
        verify(balanceHoldRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(HoldStatus.HELD);
        assertThat(captor.getValue().getPayerId()).isEqualTo(1L);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("중복 결제 참조 - DuplicatePaymentException")
    void createHoldDuplicate() {
        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(true);

        assertThatThrownBy(() -> paymentHoldService.createHold(request("300.00", "USDT")))
            .isInstanceOf(DuplicatePaymentException.class);

        verify(userBalanceRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("송신자를 찾을 수 없음 - SENDER_NOT_FOUND")
    void createHoldSenderNotFound() {
        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentHoldService.createHold(request("300.00", "USDT")))
            .isInstanceOf(PaymentException.class)
            .extracting(e -> ((PaymentException) e).getErrorCode())
            .isEqualTo("SENDER_NOT_FOUND");
    }

    @Test
    @DisplayName("수신자를 찾을 수 없음 - RECEIVER_NOT_FOUND")
    void createHoldReceiverNotFound() {
        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentHoldService.createHold(request("300.00", "USDT")))
            .isInstanceOf(PaymentException.class)
            .extracting(e -> ((PaymentException) e).getErrorCode())
            .isEqualTo("RECEIVER_NOT_FOUND");
    }

    @Test
    @DisplayName("통화 불일치 - CURRENCY_MISMATCH")
    void createHoldCurrencyMismatch() {
        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));

        assertThatThrownBy(() -> paymentHoldService.createHold(request("300.00", "USDC")))
            .isInstanceOf(PaymentException.class)
            .extracting(e -> ((PaymentException) e).getErrorCode())
            .isEqualTo("CURRENCY_MISMATCH");

        verify(balanceHoldRepository, never()).save(any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("가용 잔액 부족 - InsufficientBalanceException")
    void createHoldInsufficientBalance() {
        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));

        assertThatThrownBy(() -> paymentHoldService.createHold(request("5000.00", "USDT")))
            .isInstanceOf(InsufficientBalanceException.class);

        verify(balanceHoldRepository, never()).save(any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("attachPgTransaction은 pgTransactionId와 요청 시각을 기록한다")
    void attachPgTransaction() {
        Payment payment = pendingPayment();
        when(paymentRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(payment));

        Payment result = paymentHoldService.attachPgTransaction("PAY-001", "PGTX-001");

        assertThat(result.getPgTransactionId()).isEqualTo("PGTX-001");
        assertThat(result.getPgRequestedAt()).isNotNull();
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("PG 요청 실패 시 가예약이 해제되고 결제는 FAILED가 된다")
    void releaseOnInitiationFailure() {
        senderBalance.hold(new BigDecimal("300.00"));
        Payment payment = pendingPayment();
        BalanceHold hold = BalanceHold.create(
            "PAY-001", 1L, new BigDecimal("300.00"), "USDT", FIXED_CLOCK);

        when(paymentRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(payment));
        when(balanceHoldRepository.findByPaymentReference("PAY-001")).thenReturn(Optional.of(hold));
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));

        paymentHoldService.releaseOnInitiationFailure("PAY-001", "Failed to reach Mock PG");

        assertThat(senderBalance.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(senderBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Failed to reach Mock PG");
    }
}
