package com.payment.stablesettlement.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.stablesettlement.application.exception.DuplicatePaymentException;
import com.payment.stablesettlement.application.exception.InsufficientBalanceException;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.outbox.OutboxEvent;
import com.payment.stablesettlement.domain.outbox.OutboxEventRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentTransactionServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private UserBalanceRepository userBalanceRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TransactionSigner transactionSigner;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private final Clock clock = Clock.fixed(
            Instant.parse("2025-01-15T10:00:00Z"), ZoneId.of("UTC"));

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private PaymentTransactionService paymentTransactionService;

    private UserBalance senderBalance;
    private UserBalance receiverBalance;

    @BeforeEach
    void setUp() {
        paymentTransactionService = new PaymentTransactionService(
                paymentRepository, userBalanceRepository, outboxEventRepository,
                objectMapper, clock, meterRegistry, transactionSigner);

        senderBalance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        receiverBalance = new UserBalance(2L, new BigDecimal("500.00"), "USDT");
    }

    @Test
    @DisplayName("정상 결제 실행 - 잔액 차감/증가, Payment COMPLETED, Outbox 저장")
    void executePaymentSuccess() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("300.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionSigner.signTransaction(any(Payment.class))).thenReturn("test-signature");

        Payment result = paymentTransactionService.executePayment(request);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(result.getPaymentReference()).isEqualTo("PAY-001");
        assertThat(result.getTransactionSignature()).isEqualTo("test-signature");
        assertThat(senderBalance.getBalance()).isEqualByComparingTo(new BigDecimal("700.00"));
        assertThat(receiverBalance.getBalance()).isEqualByComparingTo(new BigDecimal("800.00"));

        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(transactionSigner).signTransaction(any(Payment.class));

        assertThat(meterRegistry.counter("payment.processed", "status", "success").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("중복 결제 참조 - DuplicatePaymentException")
    void executePaymentDuplicate() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(true);

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
                .isInstanceOf(DuplicatePaymentException.class);

        verify(userBalanceRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("송신자를 찾을 수 없음 - PaymentException")
    void executePaymentSenderNotFound() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Sender not found");
    }

    @Test
    @DisplayName("수신자를 찾을 수 없음 - PaymentException")
    void executePaymentReceiverNotFound() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Receiver not found");
    }

    @Test
    @DisplayName("잔액 부족 - InsufficientBalanceException")
    void executePaymentInsufficientBalance() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("5000.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("통화 불일치 - PaymentException (CURRENCY_MISMATCH)")
    void executePaymentCurrencyMismatch() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDC");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("currency");

        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("Outbox 이벤트가 올바른 페이로드로 저장된다 (transactionSignature 포함)")
    void executePaymentOutboxEventPayload() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionSigner.signTransaction(any(Payment.class))).thenReturn("sig-abc");

        paymentTransactionService.executePayment(request);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getAggregateType()).isEqualTo("Payment");
        assertThat(savedEvent.getAggregateId()).isEqualTo("PAY-001");
        assertThat(savedEvent.getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(savedEvent.getPayload()).contains("PAY-001");
        assertThat(savedEvent.getPayload()).contains("transactionSignature");
        assertThat(savedEvent.getPayload()).contains("sig-abc");
        assertThat(savedEvent.isPublished()).isFalse();
    }

    @Test
    @DisplayName("서명 실패 시 PaymentException 전파")
    void executePaymentSigningFailure() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(senderBalance));
        when(userBalanceRepository.findByUserId(2L)).thenReturn(Optional.of(receiverBalance));
        when(transactionSigner.signTransaction(any(Payment.class)))
                .thenThrow(new PaymentException("SIGNING_FAILED", "Transaction signing failed"));

        assertThatThrownBy(() -> paymentTransactionService.executePayment(request))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo("SIGNING_FAILED");

        assertThat(meterRegistry.counter("payment.processed", "status", "failure").count())
                .isEqualTo(1.0);
    }
}
