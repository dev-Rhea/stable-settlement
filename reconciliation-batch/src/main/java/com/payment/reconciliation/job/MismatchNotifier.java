package com.payment.reconciliation.job;

import com.payment.reconciliation.domain.ReconciliationResult;
import java.util.List;

/**
 * MATCHED 가 아닌 대사 결과를 운영 알림 경로로 내보낸다.
 */
public interface MismatchNotifier {

    String EVENT_TYPE = "RECONCILIATION_MISMATCH_DETECTED";

    void notifyMismatches(List<ReconciliationResult> mismatches);
}
