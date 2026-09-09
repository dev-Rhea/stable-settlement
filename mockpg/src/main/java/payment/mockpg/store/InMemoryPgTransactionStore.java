package payment.mockpg.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import payment.mockpg.domain.PgTransaction;

@Component
public class InMemoryPgTransactionStore {

    private final Map<String, PgTransaction> transactions = new ConcurrentHashMap<>();

    public void save(PgTransaction pgTransaction) {
        transactions.put(pgTransaction.getPgTransactionId(), pgTransaction);
    }

    public Optional<PgTransaction> find(String pgTransactionId) {
        return Optional.ofNullable(transactions.get(pgTransactionId));
    }
}
