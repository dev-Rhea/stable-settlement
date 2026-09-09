package com.payment.reconciliation.pg;

import com.payment.reconciliation.domain.LedgerEntry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Mock PG 가 내려주는 일일 거래 CSV 를 외부 원장 항목으로 변환한다.
 * 승인(APPROVED)된 거래만 실제로 돈이 오간 것이므로 대사 대상이다.
 */
@Component
public class PgSettlementCsvParser {

    private static final String APPROVED = "APPROVED";
    private static final int COLUMN_COUNT = 6;

    private static final int PAYMENT_REFERENCE = 1;
    private static final int AMOUNT = 2;
    private static final int CURRENCY = 3;
    private static final int STATUS = 4;

    public List<LedgerEntry> parse(String csv) {
        List<LedgerEntry> entries = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return entries;
        }

        String[] lines = csv.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] columns = line.split(",", -1);
            if (columns.length < COLUMN_COUNT) {
                throw new IllegalArgumentException(
                    "Malformed PG settlement row at line " + (i + 1) + ": " + line);
            }

            if (!APPROVED.equals(columns[STATUS].trim())) {
                continue;
            }

            entries.add(new LedgerEntry(
                columns[PAYMENT_REFERENCE].trim(),
                new BigDecimal(columns[AMOUNT].trim()),
                columns[CURRENCY].trim()));
        }

        return entries;
    }
}
