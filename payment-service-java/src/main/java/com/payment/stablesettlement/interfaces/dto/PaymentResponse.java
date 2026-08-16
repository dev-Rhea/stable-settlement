package com.payment.stablesettlement.interfaces.dto;

import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.domain.payment.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        String paymentReference,
        Long senderId,
        Long receiverId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        boolean signed,
        String pgTransactionId,
        LocalDateTime createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentReference(),
                payment.getSenderId(),
                payment.getReceiverId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getTransactionSignature() != null
                        && !"UNSIGNED".equals(payment.getTransactionSignature()),
                payment.getPgTransactionId(),
                payment.getCreatedAt()
        );
    }
}
