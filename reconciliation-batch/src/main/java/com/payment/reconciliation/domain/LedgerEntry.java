package com.payment.reconciliation.domain;

import java.math.BigDecimal;

/**
 * 대사 대상 한 건. 내부/외부 어느 쪽 원장에서 왔는지는 호출자가 구분한다.
 *
 * @param paymentReference 두 원장을 잇는 키
 * @param amount           거래 금액
 * @param currency         통화
 */
public record LedgerEntry(
    String paymentReference,
    BigDecimal amount,
    String currency
) {
}
