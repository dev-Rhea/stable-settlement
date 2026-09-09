package com.payment.reconciliation.job;

import com.payment.reconciliation.domain.ReconciliationResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Kafka 없이 배치만 돌려볼 때 쓰는 대체 알림 경로.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "reconciliation.notify.kafka-enabled", havingValue = "false")
public class LoggingMismatchNotifier implements MismatchNotifier {

    @Override
    public void notifyMismatches(List<ReconciliationResult> mismatches) {
        for (ReconciliationResult mismatch : mismatches) {
            log.warn("{}: date={}, reference={}, status={}, internal={}, external={}",
                EVENT_TYPE, mismatch.reconciliationDate(), mismatch.paymentReference(),
                mismatch.status(), mismatch.internalAmount(), mismatch.externalAmount());
        }
    }
}
