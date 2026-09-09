package com.payment.reconciliation.job;

import com.payment.reconciliation.domain.LedgerEntry;
import com.payment.reconciliation.domain.ReconciliationResult;
import com.payment.reconciliation.pg.PgLedgerSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 대사 Job 테스트에서 외부 의존(Mock PG, Kafka)을 대체한다.
 */
@TestConfiguration
public class ReconciliationJobTestSupport {

    /**
     * 날짜별 외부 원장을 테스트가 직접 채워 넣는 스텁.
     */
    public static class StubPgLedgerSource implements PgLedgerSource {

        private final Map<LocalDate, List<LedgerEntry>> ledgers = new LinkedHashMap<>();

        public void put(LocalDate date, List<LedgerEntry> entries) {
            ledgers.put(date, entries);
        }

        @Override
        public List<LedgerEntry> fetchDailyLedger(LocalDate date) {
            return ledgers.getOrDefault(date, List.of());
        }
    }

    /**
     * 발행된 불일치를 모아두고, 필요하면 한 번 실패시킬 수 있는 알림 스텁.
     * 재시작 의미론 검증에 쓴다.
     */
    public static class RecordingMismatchNotifier implements MismatchNotifier {

        private final List<ReconciliationResult> published = new ArrayList<>();
        private boolean failNextCall;

        public void failNextCall() {
            this.failNextCall = true;
        }

        public List<ReconciliationResult> published() {
            return published;
        }

        public void reset() {
            published.clear();
            failNextCall = false;
        }

        @Override
        public void notifyMismatches(List<ReconciliationResult> mismatches) {
            if (failNextCall) {
                failNextCall = false;
                throw new IllegalStateException("notification channel is down");
            }
            published.addAll(mismatches);
        }
    }

    @Bean
    @Primary
    public StubPgLedgerSource stubPgLedgerSource() {
        return new StubPgLedgerSource();
    }

    @Bean
    @Primary
    public RecordingMismatchNotifier recordingMismatchNotifier() {
        return new RecordingMismatchNotifier();
    }

    public static LedgerEntry entry(String reference, String amount) {
        return new LedgerEntry(reference, new BigDecimal(amount), "USDT");
    }
}
