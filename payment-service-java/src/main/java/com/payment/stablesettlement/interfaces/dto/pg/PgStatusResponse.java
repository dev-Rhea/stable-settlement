package com.payment.stablesettlement.interfaces.dto.pg;

/**
 * Mock PG 재조회 응답. {@code status}는 RECEIVED(미결정) / APPROVED / REJECTED 중 하나다.
 */
public record PgStatusResponse(
    String pgTransactionId,
    String paymentReference,
    String status
) {

}
