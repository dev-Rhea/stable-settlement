package com.payment.reconciliation.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.reconciliation.domain.LedgerEntry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgSettlementCsvParserTest {

    private static final String HEADER =
        "pgTransactionId,paymentReference,amount,currency,status,decidedAt\n";

    private final PgSettlementCsvParser parser = new PgSettlementCsvParser();

    @Test
    @DisplayName("승인 거래만 외부 원장 항목으로 읽는다")
    void parsesApprovedRowsOnly() {
        String csv = HEADER
            + "PGTX-1,PAY-001,100.00,USDT,APPROVED,2025-01-15T10:00:00\n"
            + "PGTX-2,PAY-002,200.00,USDT,REJECTED,2025-01-15T10:01:00\n"
            + "PGTX-3,PAY-003,300.00,USDT,APPROVED,2025-01-15T10:02:00\n";

        List<LedgerEntry> entries = parser.parse(csv);

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::paymentReference)
            .containsExactly("PAY-001", "PAY-003");
        assertThat(entries.get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(entries.get(0).currency()).isEqualTo("USDT");
    }

    @Test
    @DisplayName("헤더만 있거나 비어 있으면 빈 목록")
    void parsesEmptyLedger() {
        assertThat(parser.parse(HEADER)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("CRLF 개행과 마지막 빈 줄을 견딘다")
    void parsesCrlfAndTrailingBlankLine() {
        String csv = HEADER.replace("\n", "\r\n")
            + "PGTX-1,PAY-001,100.00,USDT,APPROVED,2025-01-15T10:00:00\r\n"
            + "\r\n";

        assertThat(parser.parse(csv)).hasSize(1);
    }

    @Test
    @DisplayName("열이 모자란 행은 조용히 넘기지 않고 실패시킨다")
    void rejectsMalformedRow() {
        String csv = HEADER + "PGTX-1,PAY-001,100.00\n";

        assertThatThrownBy(() -> parser.parse(csv))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Malformed");
    }
}
