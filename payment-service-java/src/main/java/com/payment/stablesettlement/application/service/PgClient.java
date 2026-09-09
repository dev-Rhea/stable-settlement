package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.interfaces.dto.pg.PgResult;
import java.math.BigDecimal;
import java.util.Optional;

public interface PgClient {
    String requestApproval(String paymentReference, BigDecimal amount, String currency);

    /**
     * PG 승인 결과를 재조회한다. 아직 결정되지 않았거나 조회에 실패하면 비어 있는 값을 반환한다.
     */
    Optional<PgResult> queryResult(String pgTransactionId);
}
