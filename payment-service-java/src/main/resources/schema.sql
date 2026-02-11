CREATE TABLE IF NOT EXISTS payment (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_reference     VARCHAR(255) NOT NULL UNIQUE,
    sender_id             BIGINT NOT NULL,
    receiver_id           BIGINT NOT NULL,
    amount                DECIMAL(20, 2) NOT NULL,
    currency              VARCHAR(10) NOT NULL,
    status                VARCHAR(20) NOT NULL,
    failure_reason        VARCHAR(500),
    transaction_signature TEXT,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6),
    INDEX idx_payment_sender (sender_id),
    INDEX idx_payment_receiver (receiver_id)
);

CREATE TABLE IF NOT EXISTS user_balance (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id  BIGINT NOT NULL UNIQUE,
    balance  DECIMAL(20, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    version  BIGINT NOT NULL DEFAULT 0,
    INDEX idx_balance_user (user_id)
);

CREATE TABLE IF NOT EXISTS outbox_event (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT NOT NULL,
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     DATETIME(6) NOT NULL,
    published_at   DATETIME(6),
    INDEX idx_outbox_unpublished (published, created_at)
);

-- Phase 3: Settlement Processor tables

CREATE TABLE IF NOT EXISTS merchant_fee_policy (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id    BIGINT NOT NULL,
    fee_type       VARCHAR(20) NOT NULL,
    fee_value      DECIMAL(10, 4) NOT NULL,
    currency       VARCHAR(10) NOT NULL,
    min_fee        DECIMAL(20, 2),
    max_fee        DECIMAL(20, 2),
    effective_from DATE NOT NULL,
    effective_to   DATE,
    UNIQUE KEY uk_merchant_currency_effective (merchant_id, currency, effective_from)
);

CREATE TABLE IF NOT EXISTS settlement_detail (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_reference VARCHAR(255) NOT NULL UNIQUE,
    merchant_id       BIGINT NOT NULL,
    gross_amount      DECIMAL(20, 2) NOT NULL,
    fee_amount        DECIMAL(20, 2) NOT NULL,
    net_amount        DECIMAL(20, 2) NOT NULL,
    fee_type          VARCHAR(20) NOT NULL,
    fee_rate          DECIMAL(10, 4) NOT NULL,
    currency          VARCHAR(10) NOT NULL,
    settled_at        DATETIME(6) NOT NULL,
    INDEX idx_settlement_merchant (merchant_id),
    INDEX idx_settlement_settled (settled_at)
);

CREATE TABLE IF NOT EXISTS dw_settlement_summary (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    summary_date  DATE NOT NULL,
    merchant_id   BIGINT NOT NULL,
    currency      VARCHAR(10) NOT NULL,
    total_count   INT NOT NULL DEFAULT 0,
    total_gross   DECIMAL(20, 2) NOT NULL DEFAULT 0,
    total_fee     DECIMAL(20, 2) NOT NULL DEFAULT 0,
    total_net     DECIMAL(20, 2) NOT NULL DEFAULT 0,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    UNIQUE KEY uk_summary_date_merchant_currency (summary_date, merchant_id, currency)
);

-- Phase 4: KMS tables

CREATE TABLE IF NOT EXISTS signing_key (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT NOT NULL UNIQUE,
    encrypted_private_key TEXT NOT NULL,
    encrypted_data_key    TEXT NOT NULL,
    kms_key_id            VARCHAR(255) NOT NULL,
    public_key            TEXT NOT NULL,
    algorithm             VARCHAR(50) NOT NULL DEFAULT 'SHA256withECDSA',
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6),
    INDEX idx_signing_key_user (user_id)
);

CREATE TABLE IF NOT EXISTS merchant_sensitive_data (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id           BIGINT NOT NULL,
    encrypted_account_info TEXT NOT NULL,
    encrypted_data_key    TEXT NOT NULL,
    kms_key_id            VARCHAR(255) NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    UNIQUE KEY uk_merchant_sensitive (merchant_id)
);
