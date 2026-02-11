package com.payment.stablesettlement.application.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends PaymentException {

    public InsufficientBalanceException(Long userId, BigDecimal balance, BigDecimal amount) {
        super("INSUFFICIENT_BALANCE",
                String.format("User %d has insufficient balance: available=%s, required=%s",
                        userId, balance.toPlainString(), amount.toPlainString()));
    }
}
