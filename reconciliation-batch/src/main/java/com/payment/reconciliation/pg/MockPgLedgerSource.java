package com.payment.reconciliation.pg;

import com.payment.reconciliation.domain.LedgerEntry;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Mock PG 의 일일 거래 CSV(GET /pg/settlements/{date})를 외부 원장으로 사용한다.
 */
@Component
@RequiredArgsConstructor
public class MockPgLedgerSource implements PgLedgerSource {

    private final RestClient pgRestClient;
    private final PgSettlementCsvParser csvParser;

    @Override
    public List<LedgerEntry> fetchDailyLedger(LocalDate date) {
        String csv = pgRestClient.get()
            .uri("/pg/settlements/{date}", DateTimeFormatter.ISO_LOCAL_DATE.format(date))
            .retrieve()
            .body(String.class);

        return csvParser.parse(csv);
    }
}
