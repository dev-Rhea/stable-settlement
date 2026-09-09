package com.payment.reconciliation.job;

import com.payment.reconciliation.domain.LedgerEntry;
import com.payment.reconciliation.domain.ReconciliationResult;
import com.payment.reconciliation.domain.ReconciliationStatus;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * 대사 배치가 쓰는 테이블 접근을 한곳에 모은다.
 */
@Repository
@RequiredArgsConstructor
public class ReconciliationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    public void clearExternalLedger(LocalDate date) {
        jdbcTemplate.update("DELETE FROM external_ledger_entry WHERE ledger_date = :date",
            new MapSqlParameterSource("date", date));
    }

    public int insertExternalLedger(LocalDate date, List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return 0;
        }

        SqlParameterSource[] batch = entries.stream()
            .map(entry -> new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("paymentReference", entry.paymentReference())
                .addValue("amount", entry.amount())
                .addValue("currency", entry.currency()))
            .toArray(SqlParameterSource[]::new);

        int[] updated = jdbcTemplate.batchUpdate(
            "INSERT INTO external_ledger_entry "
                + "(ledger_date, payment_reference, amount, currency) "
                + "VALUES (:date, :paymentReference, :amount, :currency)",
            batch);

        return updated.length;
    }

    /**
     * 같은 날짜로 다시 돌려도 결과가 중복되지 않도록 이전 산출물을 먼저 지운다.
     */
    public void clearWorkItemsAndResults(LocalDate date) {
        MapSqlParameterSource params = new MapSqlParameterSource("date", date);
        jdbcTemplate.update(
            "DELETE FROM reconciliation_work_item WHERE reconciliation_date = :date", params);
        jdbcTemplate.update(
            "DELETE FROM reconciliation_result WHERE reconciliation_date = :date", params);
    }

    /**
     * 내부 원장과 외부 원장을 payment_reference 로 합쳐 대사 작업 항목을 만든다.
     * 한쪽에만 있는 건도 빠짐없이 들어오도록 FULL OUTER JOIN 대신 UNION 후 집계한다.
     */
    public int buildWorkItems(LocalDate date) {
        return jdbcTemplate.update("""
            INSERT INTO reconciliation_work_item
                (reconciliation_date, payment_reference, internal_amount, external_amount, currency)
            SELECT :date, payment_reference,
                   MAX(internal_amount), MAX(external_amount), MAX(currency)
              FROM (
                    SELECT p.payment_reference   AS payment_reference,
                           p.amount              AS internal_amount,
                           CAST(NULL AS DECIMAL(20, 2)) AS external_amount,
                           p.currency            AS currency
                      FROM payment p
                     WHERE p.status = 'COMPLETED'
                       AND p.updated_at >= :dayStart
                       AND p.updated_at <  :dayEnd
                    UNION ALL
                    SELECT e.payment_reference,
                           CAST(NULL AS DECIMAL(20, 2)),
                           e.amount,
                           e.currency
                      FROM external_ledger_entry e
                     WHERE e.ledger_date = :date
                   ) merged
             GROUP BY payment_reference
            """,
            new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("dayStart", date.atStartOfDay())
                .addValue("dayEnd", date.plusDays(1).atStartOfDay()));
    }

    public Long minWorkItemId(LocalDate date) {
        return jdbcTemplate.queryForObject(
            "SELECT MIN(id) FROM reconciliation_work_item WHERE reconciliation_date = :date",
            new MapSqlParameterSource("date", date), Long.class);
    }

    public Long maxWorkItemId(LocalDate date) {
        return jdbcTemplate.queryForObject(
            "SELECT MAX(id) FROM reconciliation_work_item WHERE reconciliation_date = :date",
            new MapSqlParameterSource("date", date), Long.class);
    }

    public void saveResults(List<? extends ReconciliationResult> results) {
        LocalDateTime detectedAt = LocalDateTime.now(clock);

        SqlParameterSource[] batch = results.stream()
            .map(result -> new MapSqlParameterSource()
                .addValue("date", result.reconciliationDate())
                .addValue("paymentReference", result.paymentReference())
                .addValue("internalAmount", result.internalAmount(), Types.DECIMAL)
                .addValue("externalAmount", result.externalAmount(), Types.DECIMAL)
                .addValue("currency", result.currency())
                .addValue("status", result.status().name())
                .addValue("detectedAt", detectedAt))
            .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(
            "INSERT INTO reconciliation_result "
                + "(reconciliation_date, payment_reference, internal_amount, external_amount, "
                + " currency, status, detected_at) "
                + "VALUES (:date, :paymentReference, :internalAmount, :externalAmount, "
                + " :currency, :status, :detectedAt)",
            batch);
    }

    public List<ReconciliationResult> findMismatches(LocalDate date) {
        return jdbcTemplate.query("""
            SELECT reconciliation_date, payment_reference, internal_amount, external_amount,
                   currency, status
              FROM reconciliation_result
             WHERE reconciliation_date = :date
               AND status <> 'MATCHED'
             ORDER BY payment_reference
            """,
            new MapSqlParameterSource("date", date),
            (rs, rowNum) -> new ReconciliationResult(
                rs.getObject("reconciliation_date", java.sql.Date.class).toLocalDate(),
                rs.getString("payment_reference"),
                rs.getBigDecimal("internal_amount"),
                rs.getBigDecimal("external_amount"),
                rs.getString("currency"),
                ReconciliationStatus.valueOf(rs.getString("status"))));
    }

    public long countByStatus(LocalDate date, ReconciliationStatus status) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM reconciliation_result "
                + "WHERE reconciliation_date = :date AND status = :status",
            new MapSqlParameterSource()
                .addValue("date", date)
                .addValue("status", status.name()),
            Long.class);
        return count == null ? 0L : count;
    }
}
