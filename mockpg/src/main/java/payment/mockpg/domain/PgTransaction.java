package payment.mockpg.domain;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

@Getter
public class PgTransaction {

    private final String pgTransactionId;
    private final String paymentReference;
    private final BigDecimal amount;
    private final String currency;
    private final Instant createdAt;
    private PgTransactionStatus status;
    private Instant decidedAt;

    public PgTransaction(String pgTransactionId, String paymentReference, BigDecimal amount, String currency, Instant createdAt) {
        this.pgTransactionId = pgTransactionId;
        this.paymentReference = paymentReference;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
        this.status = PgTransactionStatus.RECEIVED;
    }

    public void decide(PgTransactionStatus decision) {
        if(this.status != PgTransactionStatus.RECEIVED) {
            throw new IllegalStateException("Transaction already decided: " + pgTransactionId);
        }
        this.status = decision;
        this.decidedAt = Instant.now();
    }
}
