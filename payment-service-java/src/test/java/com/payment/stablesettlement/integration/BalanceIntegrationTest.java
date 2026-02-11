package com.payment.stablesettlement.integration;

import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BalanceIntegrationTest extends TestContainerConfig {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserBalanceRepository userBalanceRepository;

    @BeforeEach
    void setUp() {
        userBalanceRepository.deleteAll();
        userBalanceRepository.save(new UserBalance(1L, new BigDecimal("5000.00"), "USDT"));
    }

    @Test
    @DisplayName("잔액 조회 성공")
    void getBalanceSuccess() throws Exception {
        mockMvc.perform(get("/api/v1/balances/1")
                        .header("Authorization", TestJwtHelper.bearerToken(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.balance").value(5000.00))
                .andExpect(jsonPath("$.data.currency").value("USDT"));
    }

    @Test
    @DisplayName("존재하지 않는 사용자 잔액 조회 - 404")
    void getBalanceNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/balances/999")
                        .header("Authorization", TestJwtHelper.bearerToken(999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
