package payment.mockpg.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import payment.mockpg.domain.PgTransaction;
import payment.mockpg.store.InMemoryPgTransactionStore;

/**
 * 하루치 거래 내역을 CSV 로 내보낸다. 정산 대사 배치의 외부 원장 소스다.
 */
@Service
@RequiredArgsConstructor
public class PgSettlementExporter {

    static final String CSV_HEADER =
        "pgTransactionId,paymentReference,amount,currency,status,decidedAt";

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final InMemoryPgTransactionStore transactionStore;

    public String exportCsv(LocalDate date) {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');

        for (PgTransaction transaction : transactionStore.findDecidedOn(date, ZONE)) {
            csv.append(transaction.getPgTransactionId()).append(',')
                .append(transaction.getPaymentReference()).append(',')
                .append(transaction.getAmount().toPlainString()).append(',')
                .append(transaction.getCurrency()).append(',')
                .append(transaction.getStatus()).append(',')
                .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                    transaction.getDecidedAt().atZone(ZONE).toLocalDateTime()))
                .append('\n');
        }

        return csv.toString();
    }
}
