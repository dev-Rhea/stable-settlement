package com.payment.stablesettlement.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.stablesettlement.application.service.PgClient;
import com.payment.stablesettlement.domain.hold.BalanceHoldRepository;
import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import com.payment.stablesettlement.interfaces.dto.pg.PgCallbackRequest;
import com.payment.stablesettlement.interfaces.dto.pg.PgResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PaymentIntegrationTest.StubPgClientConfig.class)
class PaymentIntegrationTest extends TestContainerConfig {

    /**
     * 통합 테스트는 Mock PG 서버를 띄우지 않는다. 승인 요청은 결정론적인 거래 ID만 돌려주고,
     * 승인/거절은 테스트가 콜백 엔드포인트를 직접 호출해 재현한다.
     */
    @TestConfiguration
    static class StubPgClientConfig {

        @Bean
        @Primary
        PgClient stubPgClient() {
            return new PgClient() {
                @Override
                public String requestApproval(String paymentReference, BigDecimal amount,
                                              String currency) {
                    return "PGTX-" + paymentReference;
                }

                @Override
                public Optional<PgResult> queryResult(String pgTransactionId) {
                    return Optional.empty();
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserBalanceRepository userBalanceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private BalanceHoldRepository balanceHoldRepository;

    @Autowired
    private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        // 사용자별 레이트 리밋 버킷(분당 10회)은 Redis에 남아 테스트끼리 공유된다.
        // DB를 비우듯 버킷도 비워, 앞선 테스트의 요청 수가 다음 테스트를 429로 막지 않게 한다.
        redissonClient.getKeys().deleteByPattern("rate-limit:*");
        paymentRepository.deleteAll();
        balanceHoldRepository.deleteAll();
        userBalanceRepository.deleteAll();

        userBalanceRepository.save(new UserBalance(1L, new BigDecimal("10000.00"), "USDT"));
        userBalanceRepository.save(new UserBalance(2L, new BigDecimal("5000.00"), "USDT"));
    }

    private void requestPayment(PaymentRequest request, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", TestJwtHelper.bearerToken(request.senderId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().is(expectedStatus));
    }

    private void sendPgCallback(String pgTransactionId, PgResult result, String reason)
        throws Exception {
        mockMvc.perform(post("/internal/pg-callback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new PgCallbackRequest(pgTransactionId, result, reason))))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("결제 요청은 202 Accepted와 PENDING 상태로 응답하고 잔액은 가예약만 된다")
    void processPaymentReturnsPending() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-INT-001", 1L, 2L, new BigDecimal("500.00"), "USDT");

        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", TestJwtHelper.bearerToken(1L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.paymentReference").value("PAY-INT-001"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.pgTransactionId").value("PGTX-PAY-INT-001"));

        UserBalance sender = userBalanceRepository.findByUserId(1L).orElseThrow();
        UserBalance receiver = userBalanceRepository.findByUserId(2L).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(sender.getHeldAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(sender.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("9500.00"));
        assertThat(receiver.getBalance()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("승인 콜백이 도착해야 잔액이 실제로 이동한다")
    void approvedCallbackMovesBalance() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-INT-002", 1L, 2L, new BigDecimal("500.00"), "USDT");
        requestPayment(request, 202);

        sendPgCallback("PGTX-PAY-INT-002", PgResult.APPROVED, null);

        Payment payment = paymentRepository.findByPaymentReference("PAY-INT-002").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        UserBalance sender = userBalanceRepository.findByUserId(1L).orElseThrow();
        UserBalance receiver = userBalanceRepository.findByUserId(2L).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo(new BigDecimal("9500.00"));
        assertThat(sender.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receiver.getBalance()).isEqualByComparingTo(new BigDecimal("5500.00"));
    }

    @Test
    @DisplayName("거절 콜백은 가예약을 해제하고 잔액을 원상 복구한다")
    void rejectedCallbackRestoresBalance() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-INT-003", 1L, 2L, new BigDecimal("500.00"), "USDT");
        requestPayment(request, 202);

        sendPgCallback("PGTX-PAY-INT-003", PgResult.REJECTED, "declined by PG");

        Payment payment = paymentRepository.findByPaymentReference("PAY-INT-003").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        UserBalance sender = userBalanceRepository.findByUserId(1L).orElseThrow();
        UserBalance receiver = userBalanceRepository.findByUserId(2L).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(sender.getHeldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receiver.getBalance()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("같은 콜백이 두 번 도착해도 잔액은 한 번만 움직인다")
    void duplicateCallbackIsIdempotent() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-INT-004", 1L, 2L, new BigDecimal("500.00"), "USDT");
        requestPayment(request, 202);

        sendPgCallback("PGTX-PAY-INT-004", PgResult.APPROVED, null);
        sendPgCallback("PGTX-PAY-INT-004", PgResult.APPROVED, null);

        UserBalance sender = userBalanceRepository.findByUserId(1L).orElseThrow();
        UserBalance receiver = userBalanceRepository.findByUserId(2L).orElseThrow();
        assertThat(sender.getBalance()).isEqualByComparingTo(new BigDecimal("9500.00"));
        assertThat(receiver.getBalance()).isEqualByComparingTo(new BigDecimal("5500.00"));
    }

    @Test
    @DisplayName("중복 결제 참조 - 409 Conflict")
    void processPaymentDuplicate() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-DUP-001", 1L, 2L, new BigDecimal("100.00"), "USDT");

        requestPayment(request, 202);

        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", TestJwtHelper.bearerToken(1L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("가용 잔액 부족 - 400 Bad Request")
    void processPaymentInsufficientBalance() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-INSUF-001", 1L, 2L, new BigDecimal("99999.00"), "USDT");

        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", TestJwtHelper.bearerToken(1L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("가예약 중인 금액은 후속 결제의 가용 잔액에서 빠진다")
    void heldAmountBlocksFollowUpPayment() throws Exception {
        requestPayment(new PaymentRequest(
            "PAY-HOLD-001", 1L, 2L, new BigDecimal("9000.00"), "USDT"), 202);

        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", TestJwtHelper.bearerToken(1L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PaymentRequest(
                    "PAY-HOLD-002", 1L, 2L, new BigDecimal("2000.00"), "USDT"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("유효성 검증 실패 - 400 Bad Request")
    void processPaymentValidationFail() throws Exception {
        String invalidRequest = """
            {
                "paymentReference": "",
                "senderId": 1,
                "receiverId": 2,
                "amount": 0,
                "currency": "USDT"
            }
            """;

        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", TestJwtHelper.bearerToken(1L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("동시성 테스트 - 같은 sender의 동시 결제")
    void concurrentPayments() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Future<Integer>> futures = new ArrayList<>();
        String token = TestJwtHelper.bearerToken(1L);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();

                    PaymentRequest request = new PaymentRequest(
                        "PAY-CONC-" + index, 1L, 2L,
                        new BigDecimal("100.00"), "USDT");

                    return mockMvc.perform(post("/api/v1/payments")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
                } catch (Exception e) {
                    return 500;
                }
            }));
        }

        long acceptedCount = futures.stream()
            .map(f -> {
                try { return f.get(); } catch (Exception e) { return 500; }
            })
            .filter(status -> status == 202)
            .count();

        executor.shutdown();

        assertThat(acceptedCount).isGreaterThan(0);

        UserBalance sender = userBalanceRepository.findByUserId(1L).orElseThrow();
        long pendingPayments = paymentRepository.findAll().stream()
            .filter(p -> p.getStatus() == PaymentStatus.PENDING)
            .count();

        // 승인 전이므로 잔액은 그대로이고, 가예약분만 쌓여 있어야 한다.
        assertThat(sender.getBalance()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(sender.getHeldAmount()).isEqualByComparingTo(
            new BigDecimal("100.00").multiply(BigDecimal.valueOf(pendingPayments)));
    }

    @Test
    @DisplayName("인증 없이 결제 요청 - 401 Unauthorized")
    void processPaymentWithoutAuth() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-NOAUTH-001", 1L, 2L, new BigDecimal("100.00"), "USDT");

        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("다른 사용자의 결제 요청 - 403 Forbidden")
    void processPaymentForbidden() throws Exception {
        PaymentRequest request = new PaymentRequest(
            "PAY-FORBID-001", 1L, 2L, new BigDecimal("100.00"), "USDT");

        mockMvc.perform(post("/api/v1/payments")
                .header("Authorization", TestJwtHelper.bearerToken(999L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("Cannot process payment for another user"));
    }
}
