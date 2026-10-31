package com.payment.reconciliation.schedule;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 매일 새벽 전날 거래를 대사한다.
 *
 * <p>runId를 날짜에서 만들기 때문에 같은 날짜는 Job 인스턴스가 하나뿐이다. 이미 COMPLETED면 Spring Batch가
 * 재실행을 거부하므로 중복 대사가 생기지 않고, FAILED로 끝났으면 다음 실행(또는 {@link #runFor} 수동 실행)이
 * 같은 인스턴스를 실패한 Step부터 재시작한다.
 */
public class DailyReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyReconciliationScheduler.class);

    public enum RunOutcome { COMPLETED, FAILED, ALREADY_COMPLETED, NOT_STARTED }

    private final JobLauncher jobLauncher;
    private final Job dailyReconciliationJob;
    private final Clock clock;

    public DailyReconciliationScheduler(JobLauncher jobLauncher, Job dailyReconciliationJob, Clock clock) {
        this.jobLauncher = jobLauncher;
        this.dailyReconciliationJob = dailyReconciliationJob;
        this.clock = clock;
    }

    @Scheduled(cron = "${reconciliation.schedule.cron:0 30 2 * * *}",
        zone = "${reconciliation.schedule.zone:Asia/Seoul}")
    public RunOutcome runDailyReconciliation() {
        return runFor(LocalDate.now(clock).minusDays(1));
    }

    public RunOutcome runFor(LocalDate reconciliationDate) {
        JobParameters parameters = new JobParametersBuilder()
            .addString("reconciliationDate", reconciliationDate.toString())
            .addString("runId", "scheduled-" + reconciliationDate)
            .toJobParameters();

        try {
            JobExecution execution = jobLauncher.run(dailyReconciliationJob, parameters);
            RunOutcome outcome = execution.getStatus() == BatchStatus.COMPLETED
                ? RunOutcome.COMPLETED
                : RunOutcome.FAILED;
            log.info("Daily reconciliation finished: date={}, status={}", reconciliationDate, execution.getStatus());
            return outcome;
        } catch (JobInstanceAlreadyCompleteException e) {
            log.info("Daily reconciliation already completed, skipping: date={}", reconciliationDate);
            return RunOutcome.ALREADY_COMPLETED;
        } catch (Exception e) {
            // 스케줄러 스레드로 예외를 던지면 다음 주기 실행까지 영향을 줄 수 있으므로 기록만 남긴다.
            log.error("Daily reconciliation could not be started: date={}", reconciliationDate, e);
            return RunOutcome.NOT_STARTED;
        }
    }
}
