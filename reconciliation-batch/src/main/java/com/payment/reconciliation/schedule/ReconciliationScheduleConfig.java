package com.payment.reconciliation.schedule;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code reconciliation.schedule.enabled=true}일 때만 켜지는 일일 자동 대사 설정.
 * 꺼져 있으면 기존처럼 실행 인자로 받은 날짜만 대사하고 종료한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "reconciliation.schedule.enabled", havingValue = "true")
public class ReconciliationScheduleConfig {

    @Bean
    public Clock reconciliationClock(@Value("${reconciliation.schedule.zone:Asia/Seoul}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }

    @Bean
    public DailyReconciliationScheduler dailyReconciliationScheduler(
        JobLauncher jobLauncher,
        @Qualifier("dailyReconciliationJob") Job dailyReconciliationJob,
        Clock reconciliationClock) {
        return new DailyReconciliationScheduler(jobLauncher, dailyReconciliationJob, reconciliationClock);
    }
}
