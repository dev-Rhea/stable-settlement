package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.DuplicatePaymentException;
import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentTransactionService paymentTransactionService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("정상 결제 처리")
    void processPaymentSuccess() throws InterruptedException {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        Payment expectedPayment = Payment.builder()
                .paymentReference("PAY-001")
                .senderId(1L)
                .receiverId(2L)
                .amount(new BigDecimal("100.00"))
                .currency("USDT")
                .clock(Clock.systemDefaultZone())
                .build();
        expectedPayment.complete(Clock.systemDefaultZone());

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(redissonClient.getLock("payment:lock:1")).thenReturn(lock);
        when(lock.tryLock(5L, 10L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(paymentTransactionService.executePayment(request)).thenReturn(expectedPayment);

        Payment result = paymentService.processPayment(request);

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("자기 자신에게 결제 시 PaymentException 발생")
    void processPaymentSelfPayment() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 1L,
                new BigDecimal("100.00"), "USDT");

        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Sender and receiver cannot be the same");

        verify(paymentRepository, never()).existsByPaymentReference(anyString());
    }

    @Test
    @DisplayName("중복 결제 참조 시 DuplicatePaymentException 발생")
    void processPaymentDuplicate() {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(true);

        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(DuplicatePaymentException.class);

        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("락 획득 실패 시 PaymentException 발생")
    void processPaymentLockTimeout() throws InterruptedException {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(redissonClient.getLock("payment:lock:1")).thenReturn(lock);
        when(lock.tryLock(5L, 10L, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Could not acquire lock");
    }

    @Test
    @DisplayName("락 획득 중 InterruptedException 발생 시 PaymentException으로 변환")
    void processPaymentLockInterrupted() throws InterruptedException {
        PaymentRequest request = new PaymentRequest("PAY-001", 1L, 2L,
                new BigDecimal("100.00"), "USDT");

        when(paymentRepository.existsByPaymentReference("PAY-001")).thenReturn(false);
        when(redissonClient.getLock("payment:lock:1")).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(new InterruptedException());

        assertThatThrownBy(() -> paymentService.processPayment(request))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("interrupted");
    }
}
