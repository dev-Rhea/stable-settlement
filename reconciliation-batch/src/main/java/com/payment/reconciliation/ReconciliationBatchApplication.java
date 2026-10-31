package com.payment.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class ReconciliationBatchApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context =
            SpringApplication.run(ReconciliationBatchApplication.class, args);

        // 스케줄 모드는 상주하며 매일 대사를 돌리므로 종료하지 않는다.
        // 그 외에는 지정한 날짜의 Job 한 번을 실행하고 종료 코드를 그대로 돌려준다.
        boolean scheduled = context.getEnvironment()
            .getProperty("reconciliation.schedule.enabled", Boolean.class, false);
        if (!scheduled) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
