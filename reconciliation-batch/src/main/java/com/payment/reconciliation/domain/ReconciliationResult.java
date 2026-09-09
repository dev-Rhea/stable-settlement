package com.payment.reconciliation.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 한 건의 대사 판정 결과. 없는 쪽 금액은 null 이다.
 */
public record ReconciliationResult(
    LocalDate reconciliationDate,
    String paymentReference,
    BigDecimal internalAmount,
    BigDecimal externalAmount,
    String currency,
    ReconciliationStatus status
) {
}
