package com.payment.reconciliation.job;

import static com.payment.reconciliation.job.ReconciliationJobTestSupport.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.job.ReconciliationJobTestSupport.RecordingMismatchNotifier;
import com.payment.reconciliation.job.ReconciliationJobTestSupport.StubPgLedgerSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
class DailyReconciliationJobTest {

    private static final LocalDate DATE = LocalDate.of(2025, 1, 15);

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("dailyReconciliationJob")
    private Job dailyReconciliationJob;

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

        // 내부 원장: PAY-001(일치), PAY-002(금액 불일치), PAY-003(내부에만 존재)
        insertInternalPayment("PAY-001", "100.00");
        insertInternalPayment("PAY-002", "200.00");
        insertInternalPayment("PAY-003", "300.00");

        // 외부 원장: PAY-001(일치), PAY-002(금액 다름), PAY-004(외부에만 존재)
        pgLedgerSource.put(DATE, List.of(
            entry("PAY-001", "100.00"),
            entry("PAY-002", "250.00"),
            entry("PAY-004", "400.00")));
    }

    private void insertInternalPayment(String reference, String amount) {
        jdbcTemplate.update(
            "INSERT INTO payment (payment_reference, amount, currency, status, updated_at) "
                + "VALUES (?, ?, 'USDT', 'COMPLETED', ?)",
            reference, new java.math.BigDecimal(amount), DATE.atTime(12, 0));
    }

    private JobParameters parameters(String runId) {
        return new JobParametersBuilder()
            .addString("reconciliationDate", DATE.toString())
            .addString("runId", runId)
            .toJobParameters();
    }

    private Map<String, String> statusByReference() {
        return jdbcTemplate.query(
                "SELECT payment_reference, status FROM reconciliation_result "
                    + "WHERE reconciliation_date = ?",
                (rs, rowNum) -> Map.entry(rs.getString(1), rs.getString(2)),
                DATE)
            .stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Test
    @DisplayName("내부/외부 원장을 대조해 네 가지 상태로 분류한다")
    void classifiesAllFourOutcomes() throws Exception {
        JobExecution execution = jobLauncher.run(dailyReconciliationJob, parameters("run-1"));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(statusByReference()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "PAY-001", ReconciliationStatus.MATCHED.name(),
            "PAY-002", ReconciliationStatus.AMOUNT_MISMATCH.name(),
            "PAY-003", ReconciliationStatus.INTERNAL_ONLY.name(),
            "PAY-004", ReconciliationStatus.EXTERNAL_ONLY.name()));
    }

    @Test
    @DisplayName("MATCHED가 아닌 건만 알림으로 발행한다")
    void notifiesMismatchesOnly() throws Exception {
        jobLauncher.run(dailyReconciliationJob, parameters("run-2"));

        assertThat(mismatchNotifier.published())
            .extracting(r -> r.paymentReference())
            .containsExactlyInAnyOrder("PAY-002", "PAY-003", "PAY-004");
    }

    @Test
    @DisplayName("같은 날짜로 다시 돌려도 대사 결과가 중복되지 않는다")
    void rerunIsIdempotent() throws Exception {
        jobLauncher.run(dailyReconciliationJob, parameters("run-3a"));
        JobExecution second = jobLauncher.run(dailyReconciliationJob, parameters("run-3b"));

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reconciliation_result WHERE reconciliation_date = ?",
            Long.class, DATE)).isEqualTo(4L);
    }

    @Test
    @DisplayName("Step 실패 후 같은 JobParameters로 재실행하면 완료된 Step은 건너뛴다")
    void restartsFromFailedStep() throws Exception {
        mismatchNotifier.failNextCall();

        JobParameters parameters = new JobParametersBuilder()
            .addString("reconciliationDate", DATE.toString())
            .addString("runId", "run-4")
            .toJobParameters();

        JobExecution failed = jobLauncher.run(dailyReconciliationJob, parameters);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepStatus(failed, "reconcileStep")).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepStatus(failed, "notifyMismatchStep")).isEqualTo(BatchStatus.FAILED);

        JobExecution restarted = jobLauncher.run(dailyReconciliationJob, parameters);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 완료된 Step 은 다시 실행되지 않으므로 재시작 실행에는 실패한 Step 만 남는다.
        assertThat(restarted.getStepExecutions())
            .extracting(StepExecution::getStepName)
            .containsExactly("notifyMismatchStep");
        assertThat(mismatchNotifier.published())
            .extracting(r -> r.paymentReference())
            .containsExactlyInAnyOrder("PAY-002", "PAY-003", "PAY-004");
    }

    private BatchStatus stepStatus(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(stepName))
            .map(StepExecution::getStatus)
            .findFirst()
            .orElseThrow(() -> new AssertionError("step not executed: " + stepName));
    }
}
