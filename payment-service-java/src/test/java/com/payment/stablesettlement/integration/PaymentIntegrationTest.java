package com.payment.stablesettlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.stablesettlement.domain.payment.PaymentRepository;
import com.payment.stablesettlement.domain.payment.PaymentStatus;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentIntegrationTest extends TestContainerConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserBalanceRepository userBalanceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        userBalanceRepository.deleteAll();

        userBalanceRepository.save(new UserBalance(1L, new BigDecimal("10000.00"), "USDT"));
        userBalanceRepository.save(new UserBalance(2L, new BigDecimal("5000.00"), "USDT"));
    }

    @Test
    @DisplayName("결제 API 정상 처리 - 201 Created")
    void processPaymentSuccess() throws Exception {
        PaymentRequest request = new PaymentRequest(
                "PAY-INT-001", 1L, 2L, new BigDecimal("500.00"), "USDT");

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", TestJwtHelper.bearerToken(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentReference").value("PAY-INT-001"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

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

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", TestJwtHelper.bearerToken(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", TestJwtHelper.bearerToken(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("잔액 부족 - 400 Bad Request")
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

        long successCount = futures.stream()
                .map(f -> {
                    try { return f.get(); } catch (Exception e) { return 500; }
                })
                .filter(status -> status == 201)
                .count();

        executor.shutdown();

        assertThat(successCount).isGreaterThan(0);

        UserBalance sender = userBalanceRepository.findByUserId(1L).orElseThrow();
        long completedPayments = paymentRepository.findAll().stream()
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .count();

        BigDecimal expectedBalance = new BigDecimal("10000.00")
                .subtract(new BigDecimal("100.00").multiply(BigDecimal.valueOf(completedPayments)));
        assertThat(sender.getBalance()).isEqualByComparingTo(expectedBalance);
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
