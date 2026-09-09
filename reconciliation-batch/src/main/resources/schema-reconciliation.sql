-- Phase 6: 대사(Reconciliation) 배치 테이블

CREATE TABLE IF NOT EXISTS external_ledger_entry (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    ledger_date       DATE NOT NULL,
    payment_reference VARCHAR(255) NOT NULL,
    amount            DECIMAL(20, 2) NOT NULL,
    currency          VARCHAR(10) NOT NULL,
    CONSTRAINT uk_external_ledger UNIQUE (ledger_date, payment_reference)
);

CREATE TABLE IF NOT EXISTS reconciliation_work_item (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    reconciliation_date DATE NOT NULL,
    payment_reference   VARCHAR(255) NOT NULL,
    internal_amount     DECIMAL(20, 2),
    external_amount     DECIMAL(20, 2),
    currency            VARCHAR(10) NOT NULL,
    CONSTRAINT uk_work_item UNIQUE (reconciliation_date, payment_reference)
);

CREATE TABLE IF NOT EXISTS reconciliation_result (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    reconciliation_date DATE NOT NULL,
    payment_reference   VARCHAR(255) NOT NULL,
    internal_amount     DECIMAL(20, 2),
    external_amount     DECIMAL(20, 2),
    currency            VARCHAR(10) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    detected_at         DATETIME(6) NOT NULL,
    CONSTRAINT uk_reconciliation_result UNIQUE (reconciliation_date, payment_reference)
);
