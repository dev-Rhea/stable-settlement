package payment.mockpg.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import payment.mockpg.domain.PgTransaction;
import payment.mockpg.domain.PgTransactionStatus;
import payment.mockpg.store.InMemoryPgTransactionStore;

class PgSettlementExporterTest {

    private InMemoryPgTransactionStore store;
    private PgSettlementExporter exporter;

    @BeforeEach
    void setUp() {
        store = new InMemoryPgTransactionStore();
        exporter = new PgSettlementExporter(store);
    }

    private PgTransaction save(String id, String reference, String amount) {
        PgTransaction transaction = new PgTransaction(
            id, reference, new BigDecimal(amount), "USDT", Instant.now());
        store.save(transaction);
        return transaction;
    }

    @Test
    @DisplayName("결정된 거래만 CSV로 내보낸다")
    void exportsDecidedTransactionsOnly() {
        save("PGTX-1", "PAY-001", "100.00").decide(PgTransactionStatus.APPROVED);
        save("PGTX-2", "PAY-002", "200.00").decide(PgTransactionStatus.REJECTED);
        save("PGTX-3", "PAY-003", "300.00"); // 아직 미결정

        String csv = exporter.exportCsv(LocalDate.now(ZoneId.systemDefault()));
        String[] lines = csv.split("\n");

        assertThat(lines[0]).isEqualTo(PgSettlementExporter.CSV_HEADER);
        assertThat(lines).hasSize(3);
        assertThat(csv).contains("PAY-001").contains("PAY-002").doesNotContain("PAY-003");
    }

    @Test
    @DisplayName("각 행은 헤더와 같은 열 수를 갖는다")
    void rowsMatchHeaderColumnCount() {
        save("PGTX-1", "PAY-001", "100.00").decide(PgTransactionStatus.APPROVED);

        String[] lines = exporter.exportCsv(LocalDate.now(ZoneId.systemDefault())).split("\n");
        int headerColumns = PgSettlementExporter.CSV_HEADER.split(",").length;

        assertThat(lines[1].split(",")).hasSize(headerColumns);
        assertThat(lines[1]).startsWith("PGTX-1,PAY-001,100.00,USDT,APPROVED,");
    }

    @Test
    @DisplayName("해당 날짜에 결정된 거래가 없으면 헤더만 내보낸다")
    void exportsHeaderOnlyForEmptyDay() {
        save("PGTX-1", "PAY-001", "100.00").decide(PgTransactionStatus.APPROVED);

        String csv = exporter.exportCsv(LocalDate.now(ZoneId.systemDefault()).minusDays(1));

        assertThat(csv).isEqualTo(PgSettlementExporter.CSV_HEADER + "\n");
    }
}
