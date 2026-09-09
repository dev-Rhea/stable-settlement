package com.payment.reconciliation.job;

import java.math.BigDecimal;

/**
 * 대사 Step 이 한 건씩 읽어가는 작업 항목. 한쪽에만 존재하면 그쪽 금액만 채워진다.
 */
public record ReconciliationWorkItem(
    long id,
    String paymentReference,
    BigDecimal internalAmount,
    BigDecimal externalAmount,
    String currency
) {
}
