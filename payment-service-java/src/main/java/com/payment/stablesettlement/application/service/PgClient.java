package com.payment.stablesettlement.application.service;

import java.math.BigDecimal;

public interface PgClient {
    String requestApproval(String paymentReference, BigDecimal amount, String concurrency);
}
