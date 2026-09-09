package com.payment.reconciliation.engine;

import com.payment.reconciliation.domain.LedgerEntry;
import com.payment.reconciliation.domain.ReconciliationResult;
import com.payment.reconciliation.domain.ReconciliationStatus;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * 내부/외부 원장 한 쌍을 대조해 판정한다. 저장소나 배치 인프라에 의존하지 않는 순수 로직이다.
 */
@Component
public class ReconciliationMatcher {

    public ReconciliationResult match(LocalDate reconciliationDate,
                                      String paymentReference,
                                      LedgerEntry internal,
                                      LedgerEntry external) {
        if (internal == null && external == null) {
            throw new IllegalArgumentException(
                "At least one ledger entry is required: " + paymentReference);
        }

        if (external == null) {
            return new ReconciliationResult(reconciliationDate, paymentReference,
                internal.amount(), null, internal.currency(),
                ReconciliationStatus.INTERNAL_ONLY);
        }

        if (internal == null) {
            return new ReconciliationResult(reconciliationDate, paymentReference,
                null, external.amount(), external.currency(),
                ReconciliationStatus.EXTERNAL_ONLY);
        }

        // BigDecimal.equals 는 스케일까지 비교하므로 금액 비교에는 compareTo 를 써야 한다.
        boolean sameAmount = internal.amount().compareTo(external.amount()) == 0;
        ReconciliationStatus status = sameAmount
            ? ReconciliationStatus.MATCHED
            : ReconciliationStatus.AMOUNT_MISMATCH;

        return new ReconciliationResult(reconciliationDate, paymentReference,
            internal.amount(), external.amount(), internal.currency(), status);
    }
}
