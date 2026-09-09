package payment.mockpg.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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

    /**
     * 결정이 끝난 거래만 결정 시각 기준으로 조회한다. 정산 대사의 외부 원장 소스가 된다.
     */
    public List<PgTransaction> findDecidedOn(java.time.LocalDate date, java.time.ZoneId zone) {
        return transactions.values().stream()
            .filter(transaction -> transaction.getDecidedAt() != null)
            .filter(transaction ->
                transaction.getDecidedAt().atZone(zone).toLocalDate().equals(date))
            .sorted(Comparator.comparing(PgTransaction::getDecidedAt))
            .collect(Collectors.toList());
    }
}
