package payment.mockpg.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import payment.mockpg.config.MockProperties;
import payment.mockpg.domain.PgTransaction;
import payment.mockpg.domain.PgTransactionStatus;
import payment.mockpg.dto.PgApprovalRequest;
import payment.mockpg.store.InMemoryPgTransactionStore;

@Slf4j
@Service
@RequiredArgsConstructor
public class PgApprovalService {

    private final InMemoryPgTransactionStore transactionStore;
    private final PgCallbackSender pgCallbackSender;
    private final ScheduledExecutorService pgCallbackScheduler;
    private final MockProperties properties;

    public String approve(PgApprovalRequest request) {
        String pgTransactionId = "PGTX-" + UUID.randomUUID();
        PgTransaction transaction = new PgTransaction(
            pgTransactionId, request.paymentReference(), request.amount(),
            request.currency(), Instant.now()
        );

        transactionStore.save(transaction);

        long delayMs = randomDelayMs();
        pgCallbackScheduler.schedule(() -> decideAndCallback(pgTransactionId), delayMs, TimeUnit.MILLISECONDS);

        log.info("PG approval requested: pgTransactionID = {}, paymentReference = {}, delayMs = {}", pgTransactionId, request.paymentReference(), delayMs);

        return pgTransactionId;
    }

    private long randomDelayMs() {
        long min = properties.getApprovalDelay().getMinMs();
        long max = properties.getApprovalDelay().getMaxMs();
        if(min >= max) {
            return min;
        }

        return ThreadLocalRandom.current().nextLong(min, max);
    }

    private void decideAndCallback(String pgTransactionId) {
        transactionStore.find(pgTransactionId).ifPresent(transaction -> {
            boolean approved = ThreadLocalRandom.current().nextDouble() < properties.getApprovalProbability();
            PgTransactionStatus decision = approved ? PgTransactionStatus.APPROVED : PgTransactionStatus.REJECTED;
            transaction.decide(decision);
            pgCallbackSender.sendCallback(transaction, approved);
        });
    }
}
