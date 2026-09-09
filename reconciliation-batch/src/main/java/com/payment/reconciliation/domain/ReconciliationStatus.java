package com.payment.reconciliation.domain;

/**
 * 내부 원장(payment)과 외부 원장(PG 일일 거래 내역)을 대조한 결과.
 */
public enum ReconciliationStatus {

    /** 양쪽 모두 존재하고 금액도 같다. */
    MATCHED,

    /** 내부에만 있다 — PG 응답이 유실됐을 가능성. */
    INTERNAL_ONLY,

    /** 외부에만 있다 — 실제로 돈이 오갔는데 내부 기록이 없다. 가장 심각. */
    EXTERNAL_ONLY,

    /** 양쪽 모두 있으나 금액이 다르다. */
    AMOUNT_MISMATCH;

    public boolean isMismatch() {
        return this != MATCHED;
    }
}
