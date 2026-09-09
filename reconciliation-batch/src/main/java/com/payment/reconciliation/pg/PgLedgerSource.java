package com.payment.reconciliation.pg;

import com.payment.reconciliation.domain.LedgerEntry;
import java.time.LocalDate;
import java.util.List;

/**
 * 대사의 외부 원장 소스. 배치는 이 포트에만 의존하고, 실제 조회 방식은 구현체가 정한다.
 */
public interface PgLedgerSource {

    List<LedgerEntry> fetchDailyLedger(LocalDate date);
}
