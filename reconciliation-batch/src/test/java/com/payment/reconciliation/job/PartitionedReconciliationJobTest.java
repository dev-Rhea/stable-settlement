package com.payment.reconciliation.job;

import static com.payment.reconciliation.job.ReconciliationJobTestSupport.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.payment.reconciliation.domain.LedgerEntry;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.job.ReconciliationJobTestSupport.RecordingMismatchNotifier;
import com.payment.reconciliation.job.ReconciliationJobTestSupport.StubPgLedgerSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(ReconciliationJobTestSupport.class)
class PartitionedReconciliationJobTest {

    private static final LocalDate DATE = LocalDate.of(2025, 2, 20);
    private static final int MATCHED_COUNT = 250;
    private static final int INTERNAL_ONLY_COUNT = 30;
    private static final int EXTERNAL_ONLY_COUNT = 20;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("partitionedReconciliationJob")
    private Job partitionedReconciliationJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StubPgLedgerSource pgLedgerSource;

    @Autowired
    private RecordingMismatchNotifier mismatchNotifier;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM reconciliation_result");
        jdbcTemplate.update("DELETE FROM reconciliation_work_item");
        jdbcTemplate.update("DELETE FROM external_ledger_entry");
        jdbcTemplate.update("DELETE FROM payment");
        mismatchNotifier.reset();

        List<LedgerEntry> external = new ArrayList<>();

        for (int i = 0; i < MATCHED_COUNT; i++) {
            String reference = "PAY-M-" + i;
            insertInternalPayment(reference, "100.00");
            external.add(entry(reference, "100.00"));
        }
        for (int i = 0; i < INTERNAL_ONLY_COUNT; i++) {
            insertInternalPayment("PAY-I-" + i, "100.00");
        }
        for (int i = 0; i < EXTERNAL_ONLY_COUNT; i++) {
            external.add(entry("PAY-E-" + i, "100.00"));
        }

        pgLedgerSource.put(DATE, external);
    }

    private void insertInternalPayment(String reference, String amount) {
        jdbcTemplate.update(
            "INSERT INTO payment (payment_reference, amount, currency, status, updated_at) "
                + "VALUES (?, ?, 'USDT', 'COMPLETED', ?)",
            reference, new BigDecimal(amount), DATE.atTime(12, 0));
    }

    private long countByStatus(ReconciliationStatus status) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reconciliation_result "
                + "WHERE reconciliation_date = ? AND status = ?",
            Long.class, DATE, status.name());
    }

    @Test
    @DisplayName("파티셔닝 Job도 전 건을 빠짐없이, 중복 없이 대사한다")
    void partitionedJobCoversEveryItemExactlyOnce() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
            .addString("reconciliationDate", DATE.toString())
            .addString("runId", "partitioned-1")
            .toJobParameters();

        JobExecution execution = jobLauncher.run(partitionedReconciliationJob, parameters);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        int total = MATCHED_COUNT + INTERNAL_ONLY_COUNT + EXTERNAL_ONLY_COUNT;
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reconciliation_result WHERE reconciliation_date = ?",
            Long.class, DATE)).isEqualTo((long) total);

        assertThat(countByStatus(ReconciliationStatus.MATCHED)).isEqualTo(MATCHED_COUNT);
        assertThat(countByStatus(ReconciliationStatus.INTERNAL_ONLY))
            .isEqualTo(INTERNAL_ONLY_COUNT);
        assertThat(countByStatus(ReconciliationStatus.EXTERNAL_ONLY))
            .isEqualTo(EXTERNAL_ONLY_COUNT);

        assertThat(mismatchNotifier.published())
            .hasSize(INTERNAL_ONLY_COUNT + EXTERNAL_ONLY_COUNT);
    }

    @Test
    @DisplayName("대사 Step이 여러 워커로 나뉘어 실행된다")
    void splitsWorkAcrossWorkerSteps() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
            .addString("reconciliationDate", DATE.toString())
            .addString("runId", "partitioned-2")
            .toJobParameters();

        JobExecution execution = jobLauncher.run(partitionedReconciliationJob, parameters);

        List<StepExecution> workers = execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().startsWith("reconcileStep:partition"))
            .toList();

        assertThat(workers).hasSizeGreaterThan(1);
        assertThat(workers).allSatisfy(
            worker -> assertThat(worker.getStatus()).isEqualTo(BatchStatus.COMPLETED));
        assertThat(workers.stream().mapToLong(StepExecution::getWriteCount).sum())
            .isEqualTo((long) (MATCHED_COUNT + INTERNAL_ONLY_COUNT + EXTERNAL_ONLY_COUNT));
    }
}
