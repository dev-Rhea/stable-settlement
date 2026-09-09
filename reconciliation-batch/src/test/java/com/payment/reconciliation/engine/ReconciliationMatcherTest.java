package com.payment.reconciliation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.reconciliation.domain.LedgerEntry;
import com.payment.reconciliation.domain.ReconciliationResult;
import com.payment.reconciliation.domain.ReconciliationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconciliationMatcherTest {

    private static final LocalDate DATE = LocalDate.of(2025, 1, 15);

    private final ReconciliationMatcher matcher = new ReconciliationMatcher();

    private LedgerEntry entry(String amount) {
        return new LedgerEntry("PAY-001", new BigDecimal(amount), "USDT");
    }

    @Test
    @DisplayName("양쪽에 있고 금액이 같으면 MATCHED")
    void matched() {
        ReconciliationResult result =
            matcher.match(DATE, "PAY-001", entry("100.00"), entry("100.00"));

        assertThat(result.status()).isEqualTo(ReconciliationStatus.MATCHED);
        assertThat(result.status().isMismatch()).isFalse();
        assertThat(result.internalAmount()).isEqualByComparingTo("100.00");
        assertThat(result.externalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("스케일만 다른 금액은 일치로 본다")
    void matchedIgnoresScale() {
        ReconciliationResult result =
            matcher.match(DATE, "PAY-001", entry("100.00"), entry("100.0"));

        assertThat(result.status()).isEqualTo(ReconciliationStatus.MATCHED);
    }

    @Test
    @DisplayName("내부에만 있으면 INTERNAL_ONLY")
    void internalOnly() {
        ReconciliationResult result = matcher.match(DATE, "PAY-001", entry("100.00"), null);

        assertThat(result.status()).isEqualTo(ReconciliationStatus.INTERNAL_ONLY);
        assertThat(result.internalAmount()).isEqualByComparingTo("100.00");
        assertThat(result.externalAmount()).isNull();
    }

    @Test
    @DisplayName("외부에만 있으면 EXTERNAL_ONLY")
    void externalOnly() {
        ReconciliationResult result = matcher.match(DATE, "PAY-001", null, entry("100.00"));

        assertThat(result.status()).isEqualTo(ReconciliationStatus.EXTERNAL_ONLY);
        assertThat(result.internalAmount()).isNull();
        assertThat(result.externalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("금액이 다르면 AMOUNT_MISMATCH")
    void amountMismatch() {
        ReconciliationResult result =
            matcher.match(DATE, "PAY-001", entry("100.00"), entry("120.00"));

        assertThat(result.status()).isEqualTo(ReconciliationStatus.AMOUNT_MISMATCH);
        assertThat(result.status().isMismatch()).isTrue();
    }

    @Test
    @DisplayName("양쪽 모두 없으면 대사 대상이 아니다")
    void bothMissing() {
        assertThatThrownBy(() -> matcher.match(DATE, "PAY-001", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
