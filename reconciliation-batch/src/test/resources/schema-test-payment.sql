-- 내부 원장(payment)은 결제 서비스가 소유한다. 대사 배치는 읽기만 하므로
-- 테스트에서는 대사에 필요한 컬럼만 갖춘 최소 테이블을 만든다.
CREATE TABLE IF NOT EXISTS payment (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_reference VARCHAR(255) NOT NULL,
    amount            DECIMAL(20, 2) NOT NULL,
    currency          VARCHAR(10) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    updated_at        DATETIME(6),
    CONSTRAINT uk_test_payment_reference UNIQUE (payment_reference)
);
