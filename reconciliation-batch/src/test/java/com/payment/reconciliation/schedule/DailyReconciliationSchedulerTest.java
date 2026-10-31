package com.payment.reconciliation.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.reconciliation.schedule.DailyReconciliationScheduler.RunOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;

class DailyReconciliationSchedulerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // 2025-01-16 02:30 KST (UTC로는 전날 17:30) — 대사 대상은 KST 기준 전날인 2025-01-15
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-01-15T17:30:00Z"), SEOUL);

    private JobLauncher jobLauncher;
    private Job job;
    private DailyReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        jobLauncher = mock(JobLauncher.class);
        job = mock(Job.class);
        scheduler = new DailyReconciliationScheduler(jobLauncher, job, CLOCK);
    }

    private JobExecution execution(BatchStatus status) {
        JobExecution execution = new JobExecution(1L);
        execution.setStatus(status);
        return execution;
    }

    @Test
    @DisplayName("한국 시간 기준 전날을 대사 대상으로, 날짜별 고정 runId로 Job을 실행한다")
    void runsForPreviousDayInSeoul() throws Exception {
        when(jobLauncher.run(eq(job), any())).thenReturn(execution(BatchStatus.COMPLETED));

        RunOutcome outcome = scheduler.runDailyReconciliation();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(job), captor.capture());
        assertThat(captor.getValue().getString("reconciliationDate")).isEqualTo("2025-01-15");
        assertThat(captor.getValue().getString("runId")).isEqualTo("scheduled-2025-01-15");
        assertThat(outcome).isEqualTo(RunOutcome.COMPLETED);
    }

    @Test
    @DisplayName("같은 날짜가 이미 대사 완료됐으면 다시 돌리지 않고 건너뛴다")
    void skipsWhenAlreadyCompleted() throws Exception {
        when(jobLauncher.run(eq(job), any()))
            .thenThrow(new JobInstanceAlreadyCompleteException("done"));

        assertThat(scheduler.runDailyReconciliation()).isEqualTo(RunOutcome.ALREADY_COMPLETED);
    }

    @Test
    @DisplayName("Job이 실패로 끝나면 FAILED를 돌려준다 — 같은 파라미터로 재실행하면 실패한 Step부터 재시작된다")
    void reportsFailedExecution() throws Exception {
        when(jobLauncher.run(eq(job), any())).thenReturn(execution(BatchStatus.FAILED));

        assertThat(scheduler.runDailyReconciliation()).isEqualTo(RunOutcome.FAILED);
    }

    @Test
    @DisplayName("실행을 시작하지 못해도 스케줄러 스레드로 예외를 던지지 않는다")
    void doesNotPropagateLaunchErrors() throws Exception {
        when(jobLauncher.run(eq(job), any()))
            .thenThrow(new JobExecutionAlreadyRunningException("running"));

        assertThat(scheduler.runDailyReconciliation()).isEqualTo(RunOutcome.NOT_STARTED);
    }

    @Test
    @DisplayName("지정한 날짜로 수동 실행할 수 있다 (누락된 날짜 보충용)")
    void runsForGivenDate() throws Exception {
        when(jobLauncher.run(eq(job), any())).thenReturn(execution(BatchStatus.COMPLETED));

        scheduler.runFor(LocalDate.of(2025, 1, 10));

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher).run(eq(job), captor.capture());
        assertThat(captor.getValue().getString("reconciliationDate")).isEqualTo("2025-01-10");
    }
}
