package payment.mockpg.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgTransactionTest {

    private PgTransaction newTransaction() {
        return new PgTransaction("PGTX-001", "PAY-001", new BigDecimal("100.00"),
            "USDT", Instant.parse("2025-01-15T10:00:00Z"));
    }

    @Test
    @DisplayName("생성 직후 상태는 RECEIVED 이다")
    void createdAsReceived() {
        assertThat(newTransaction().getStatus()).isEqualTo(PgTransactionStatus.RECEIVED);
    }

    @Test
    @DisplayName("RECEIVED 상태에서 승인 결정을 반영한다")
    void decideApproved() {
        PgTransaction transaction = newTransaction();

        transaction.decide(PgTransactionStatus.APPROVED);

        assertThat(transaction.getStatus()).isEqualTo(PgTransactionStatus.APPROVED);
    }

    @Test
    @DisplayName("이미 결정된 거래는 다시 결정할 수 없다")
    void decideTwiceRejected() {
        PgTransaction transaction = newTransaction();
        transaction.decide(PgTransactionStatus.REJECTED);

        assertThatThrownBy(() -> transaction.decide(PgTransactionStatus.APPROVED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already decided");
    }
}
