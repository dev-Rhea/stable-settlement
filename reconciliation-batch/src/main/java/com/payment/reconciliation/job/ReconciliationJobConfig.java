package com.payment.reconciliation.job;

import com.payment.reconciliation.domain.LedgerEntry;
import com.payment.reconciliation.domain.ReconciliationResult;
import com.payment.reconciliation.domain.ReconciliationStatus;
import com.payment.reconciliation.engine.ReconciliationMatcher;
import com.payment.reconciliation.pg.PgLedgerSource;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 일일 대사 Job.
 *
 * <pre>
 * loadExternalLedger -> buildWorkItems -> reconcile -> notifyMismatch
 * </pre>
 *
 * 같은 {@code reconciliationDate} 로 다시 실행하면 완료된 Step 은 건너뛰고 실패한 Step 부터 재개된다.
 * 앞 두 Step 은 자기 산출물을 먼저 지우므로 재실행해도 결과가 중복되지 않는다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ReconciliationJobConfig {

    static final String DATE_PARAMETER = "reconciliationDate";
    private static final int CHUNK_SIZE = 500;
    private static final int GRID_SIZE = 4;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final PgLedgerSource pgLedgerSource;
    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationMatcher reconciliationMatcher;
    private final MismatchNotifier mismatchNotifier;

    @Bean
    public Job dailyReconciliationJob(Step loadExternalLedgerStep,
                                      Step buildWorkItemsStep,
                                      Step reconcileStep,
                                      Step notifyMismatchStep) {
        return new JobBuilder("dailyReconciliationJob", jobRepository)
            .start(loadExternalLedgerStep)
            .next(buildWorkItemsStep)
            .next(reconcileStep)
            .next(notifyMismatchStep)
            .build();
    }

    /**
     * 대사 Step 만 ID 구간으로 쪼개 병렬 처리하는 Job. 단일 스레드 Job 과 나란히 두어
     * 같은 데이터에 대한 처리 시간을 비교할 수 있다.
     */
    @Bean
    public Job partitionedReconciliationJob(Step loadExternalLedgerStep,
                                            Step buildWorkItemsStep,
                                            Step partitionedReconcileStep,
                                            Step notifyMismatchStep) {
        return new JobBuilder("partitionedReconciliationJob", jobRepository)
            .start(loadExternalLedgerStep)
            .next(buildWorkItemsStep)
            .next(partitionedReconcileStep)
            .next(notifyMismatchStep)
            .build();
    }

    @Bean
    public Step loadExternalLedgerStep() {
        return new StepBuilder("loadExternalLedgerStep", jobRepository)
            .tasklet(loadExternalLedgerTasklet(null), transactionManager)
            .build();
    }

    @Bean
    @StepScope
    public org.springframework.batch.core.step.tasklet.Tasklet loadExternalLedgerTasklet(
        @Value("#{jobParameters['" + DATE_PARAMETER + "']}") String dateText) {
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(dateText);

            List<LedgerEntry> entries = pgLedgerSource.fetchDailyLedger(date);
            reconciliationRepository.clearExternalLedger(date);
            int inserted = reconciliationRepository.insertExternalLedger(date, entries);

            contribution.incrementWriteCount(inserted);
            log.info("Loaded external ledger: date={}, entries={}", date, inserted);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step buildWorkItemsStep() {
        return new StepBuilder("buildWorkItemsStep", jobRepository)
            .tasklet(buildWorkItemsTasklet(null), transactionManager)
            .build();
    }

    @Bean
    @StepScope
    public org.springframework.batch.core.step.tasklet.Tasklet buildWorkItemsTasklet(
        @Value("#{jobParameters['" + DATE_PARAMETER + "']}") String dateText) {
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(dateText);

            reconciliationRepository.clearWorkItemsAndResults(date);
            int built = reconciliationRepository.buildWorkItems(date);

            contribution.incrementWriteCount(built);
            log.info("Built reconciliation work items: date={}, items={}", date, built);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step reconcileStep() {
        return new StepBuilder("reconcileStep", jobRepository)
            .<ReconciliationWorkItem, ReconciliationResult>chunk(CHUNK_SIZE, transactionManager)
            .reader(workItemReader(null, null, null))
            .processor(reconciliationProcessor(null))
            .writer(reconciliationWriter())
            .build();
    }

    @Bean
    public Step partitionedReconcileStep(Step reconcileStep) {
        return new StepBuilder("partitionedReconcileStep", jobRepository)
            .partitioner("reconcileStep", workItemPartitioner(null))
            .step(reconcileStep)
            .gridSize(GRID_SIZE)
            .taskExecutor(reconciliationTaskExecutor())
            .build();
    }

    @Bean
    @StepScope
    public WorkItemRangePartitioner workItemPartitioner(
        @Value("#{jobParameters['" + DATE_PARAMETER + "']}") String dateText) {
        return new WorkItemRangePartitioner(reconciliationRepository, LocalDate.parse(dateText));
    }

    @Bean
    public TaskExecutor reconciliationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(GRID_SIZE);
        executor.setMaxPoolSize(GRID_SIZE);
        executor.setThreadNamePrefix("reconcile-");
        executor.initialize();
        return executor;
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<ReconciliationWorkItem> workItemReader(
        @Value("#{jobParameters['" + DATE_PARAMETER + "']}") String dateText,
        @Value("#{stepExecutionContext['minId']}") Long minId,
        @Value("#{stepExecutionContext['maxId']}") Long maxId) {

        long from = minId != null ? minId : Long.MIN_VALUE;
        long to = maxId != null ? maxId : Long.MAX_VALUE;

        return new JdbcCursorItemReaderBuilder<ReconciliationWorkItem>()
            .name("workItemReader")
            .dataSource(dataSource)
            .sql("""
                SELECT id, payment_reference, internal_amount, external_amount, currency
                  FROM reconciliation_work_item
                 WHERE reconciliation_date = ?
                   AND id BETWEEN ? AND ?
                 ORDER BY id
                """)
            .preparedStatementSetter(ps -> {
                ps.setObject(1, LocalDate.parse(dateText));
                ps.setLong(2, from);
                ps.setLong(3, to);
            })
            .rowMapper((rs, rowNum) -> new ReconciliationWorkItem(
                rs.getLong("id"),
                rs.getString("payment_reference"),
                rs.getBigDecimal("internal_amount"),
                rs.getBigDecimal("external_amount"),
                rs.getString("currency")))
            .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<ReconciliationWorkItem, ReconciliationResult> reconciliationProcessor(
        @Value("#{jobParameters['" + DATE_PARAMETER + "']}") String dateText) {
        LocalDate date = LocalDate.parse(dateText);

        return item -> reconciliationMatcher.match(
            date,
            item.paymentReference(),
            item.internalAmount() == null ? null
                : new LedgerEntry(item.paymentReference(), item.internalAmount(), item.currency()),
            item.externalAmount() == null ? null
                : new LedgerEntry(item.paymentReference(), item.externalAmount(), item.currency()));
    }

    @Bean
    public ItemWriter<ReconciliationResult> reconciliationWriter() {
        return chunk -> reconciliationRepository.saveResults(chunk.getItems());
    }

    @Bean
    public Step notifyMismatchStep() {
        return new StepBuilder("notifyMismatchStep", jobRepository)
            .tasklet(notifyMismatchTasklet(null), transactionManager)
            .build();
    }

    @Bean
    @StepScope
    public org.springframework.batch.core.step.tasklet.Tasklet notifyMismatchTasklet(
        @Value("#{jobParameters['" + DATE_PARAMETER + "']}") String dateText) {
        return (contribution, chunkContext) -> {
            LocalDate date = LocalDate.parse(dateText);

            List<ReconciliationResult> mismatches = reconciliationRepository.findMismatches(date);
            if (!mismatches.isEmpty()) {
                mismatchNotifier.notifyMismatches(mismatches);
            }

            log.info("Reconciliation summary: date={}, matched={}, mismatches={}",
                date,
                reconciliationRepository.countByStatus(date, ReconciliationStatus.MATCHED),
                mismatches.size());
            return RepeatStatus.FINISHED;
        };
    }
}
