package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.domain.user.UserBalance;
import com.payment.stablesettlement.domain.user.UserBalanceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock
    private UserBalanceRepository userBalanceRepository;

    @InjectMocks
    private BalanceService balanceService;

    @Test
    @DisplayName("잔액 조회 성공")
    void getBalanceSuccess() {
        UserBalance userBalance = new UserBalance(1L, new BigDecimal("1000.00"), "USDT");
        when(userBalanceRepository.findByUserId(1L)).thenReturn(Optional.of(userBalance));

        UserBalance result = balanceService.getBalance(1L);

        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    @DisplayName("존재하지 않는 사용자 잔액 조회 시 예외 발생")
    void getBalanceNotFound() {
        when(userBalanceRepository.findByUserId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> balanceService.getBalance(999L))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("User not found");
    }
}
