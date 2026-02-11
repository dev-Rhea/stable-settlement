package com.payment.stablesettlement.application.exception;

public class DuplicatePaymentException extends PaymentException {

    public DuplicatePaymentException(String paymentReference) {
        super("DUPLICATE_PAYMENT",
                String.format("Payment with reference '%s' already exists", paymentReference));
    }
}
