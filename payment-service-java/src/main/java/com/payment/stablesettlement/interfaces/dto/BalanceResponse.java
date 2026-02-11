package com.payment.stablesettlement.interfaces.dto;

import com.payment.stablesettlement.domain.user.UserBalance;

import java.math.BigDecimal;

public record BalanceResponse(
        Long userId,
        BigDecimal balance,
        String currency
) {
    public static BalanceResponse from(UserBalance userBalance) {
        return new BalanceResponse(
                userBalance.getUserId(),
                userBalance.getBalance(),
                userBalance.getCurrency()
        );
    }
}
