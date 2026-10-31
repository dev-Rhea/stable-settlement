package com.payment.reconciliation.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.reconciliation.job.ReconciliationJobTestSupport;
import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

class ReconciliationScheduleConfigTest {

    @Nested
    @SpringBootTest
    @Import(ReconciliationJobTestSupport.class)
    @DisplayName("기본 설정 (1회 실행 모드)")
    class Disabled {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("스케줄러를 등록하지 않는다 — 기존처럼 인자로 받은 날짜만 대사하고 종료")
        void schedulerIsNotRegistered() {
            assertThat(context.getBeanNamesForType(DailyReconciliationScheduler.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = "reconciliation.schedule.enabled=true")
    @Import(ReconciliationJobTestSupport.class)
    @DisplayName("스케줄 모드")
    class Enabled {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("스케줄러와 한국 시간 Clock을 등록한다")
        void schedulerIsRegistered() {
            assertThat(context.getBean(DailyReconciliationScheduler.class)).isNotNull();
            assertThat(context.getBean("reconciliationClock", Clock.class).getZone())
                .isEqualTo(ZoneId.of("Asia/Seoul"));
        }
    }
}
