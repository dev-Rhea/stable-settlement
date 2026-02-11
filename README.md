# Stable Settlement

Stablecoin 기반 실시간 결제 및 정산 시스템. Spring Boot 결제 서비스가 트랜잭션을 처리하고, Kafka를 통해 Python 정산 프로세서로 이벤트를 전달하여 수수료 계산 및 DW 집계를 수행한다.

## Architecture

```
                          ┌─────────────────┐
                          │    Client        │
                          └────────┬────────┘
                                   │ REST API (JWT)
                          ┌────────▼────────┐
                          │  Payment Service │ ← Spring Boot (Java 17)
                          │    :8080         │
                          └──┬────┬────┬────┘
                             │    │    │
                    ┌────────┘    │    └────────┐
                    ▼             ▼              ▼
              ┌──────────┐ ┌──────────┐  ┌───────────┐
              │  MySQL   │ │  Redis   │  │   Kafka   │
              │  :3306   │ │  :6379   │  │   :9092   │
              └──────────┘ └──────────┘  └─────┬─────┘
                                               │ payment-events
                                         ┌─────▼──────────┐
                                         │   Settlement    │ ← Python 3.11+
                                         │   Processor     │
                                         └──┬──────────┬──┘
                                            │          │
                                       ┌────▼───┐ ┌───▼────────┐
                                       │ MySQL  │ │ LocalStack │
                                       │        │ │ KMS :4566  │
                                       └────────┘ └────────────┘
```

## Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Payment Service | Spring Boot | 3.5.10 |
| Language (Java) | Java | 17 |
| Settlement Processor | Python | 3.11+ |
| Database | MySQL | 8.0 |
| Cache / Lock | Redis + Redisson | 7.4 / 3.35.0 |
| Message Broker | Apache Kafka (KRaft) | 3.8.1 |
| KMS | AWS KMS (LocalStack) | - |
| Auth | JWT (jjwt) | 0.12.6 |
| Rate Limiting | Bucket4j + Redis | 8.10.1 |
| ORM (Python) | SQLAlchemy | 2.0+ |

## Module Structure

```
stable-settlement/
├── payment-service-java/          # Spring Boot 결제 서비스
│   └── src/main/java/.../
│       ├── domain/                # 엔티티, 레포지토리
│       │   ├── payment/           #   Payment, PaymentStatus
│       │   ├── user/              #   UserBalance (@Version 낙관적 락)
│       │   ├── outbox/            #   OutboxEvent (Transactional Outbox)
│       │   └── signing/           #   SigningKey (KMS 암호화 서명키)
│       ├── application/           # 비즈니스 로직
│       │   ├── service/           #   PaymentService, TransactionSigner
│       │   └── exception/         #   도메인 예외
│       ├── infrastructure/        # 외부 시스템 연동
│       │   ├── config/            #   Clock, Kafka, RateLimit, Redisson
│       │   ├── kafka/             #   Outbox Relayer/Publisher/Cleaner
│       │   ├── kms/               #   KmsConfig, Envelope Encryptor, NoOp
│       │   └── security/          #   JWT, SecurityConfig
│       └── interfaces/            # API 레이어
│           ├── controller/        #   REST 컨트롤러
│           └── dto/               #   Request/Response Records
│
├── settlement-processor-python/   # Python 정산 프로세서
│   ├── config/                    # Settings (frozen dataclass)
│   ├── src/
│   │   ├── consumer.py            # Kafka 컨슈머 (at-least-once)
│   │   ├── settlement_engine.py   # 수수료 계산 엔진
│   │   ├── data_cleanser.py       # DW 집계 (SELECT FOR UPDATE)
│   │   ├── kms_encryptor.py       # AES-256-GCM 봉투 암호화
│   │   ├── merchant_data_service.py # 가맹점 민감정보 암호화
│   │   └── models.py              # SQLAlchemy 모델
│   └── tests/
│
├── scripts/
│   └── init-kms.sh                # LocalStack KMS 초기화
└── docker-compose.yml             # MySQL, Redis, Kafka, LocalStack
```

## Getting Started

### Prerequisites

- Java 17+
- Python 3.11+
- Docker & Docker Compose

### 1. Infrastructure

```bash
docker compose up -d
```

5개 서비스가 기동된다: MySQL, Redis, Kafka, LocalStack(KMS), Settlement Processor.

### 2. Payment Service (Java)

```bash
# 빌드 및 테스트
./gradlew :payment-service-java:build

# 실행 (dev 프로필 - 토큰 발급 엔드포인트 활성화)
DB_URL=jdbc:mysql://localhost:3306/stable_settlement?useSSL=false&allowPublicKeyRetrieval=true \
DB_USERNAME=payment \
DB_PASSWORD=payment \
JWT_SECRET=your-256-bit-secret-key-here-must-be-long-enough \
./gradlew :payment-service-java:bootRun --args='--spring.profiles.active=dev'
```

### 3. Settlement Processor (Python)

Docker Compose로 자동 실행되지만, 로컬에서 직접 실행하려면:

```bash
cd settlement-processor-python
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

DB_URL=mysql+pymysql://payment:payment@localhost:3306/stable_settlement \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
python -m src.main
```

## API Reference

### Authentication

> `POST /api/v1/auth/token` 은 dev/test 프로필에서만 활성화된다.

```bash
# JWT 토큰 발급
curl -X POST "http://localhost:8080/api/v1/auth/token?userId=1"
```

### Payment

```bash
# 결제 요청
curl -X POST http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "paymentReference": "PAY-001",
    "senderId": 1,
    "receiverId": 2,
    "amount": "100.00",
    "currency": "USDT"
  }'
```

**Response** `201 Created`

```json
{
  "success": true,
  "data": {
    "id": 1,
    "paymentReference": "PAY-001",
    "senderId": 1,
    "receiverId": 2,
    "amount": 100.00,
    "currency": "USDT",
    "status": "COMPLETED",
    "signed": true,
    "createdAt": "2025-01-15T10:00:00"
  }
}
```

### Balance

```bash
# 잔액 조회
curl http://localhost:8080/api/v1/balances/1 \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

### Error Responses

| Status | Code | Description |
|--------|------|-------------|
| 400 | CURRENCY_MISMATCH | 통화 불일치 |
| 400 | SELF_PAYMENT | 자기 자신에게 송금 |
| 403 | - | 타인의 결제/잔액 접근 |
| 404 | SENDER_NOT_FOUND | 송신자 미존재 |
| 404 | RECEIVER_NOT_FOUND | 수신자 미존재 |
| 409 | - | 중복 결제 / 낙관적 락 충돌 |
| 429 | - | Rate limit 초과 (10 req/min) |
| 500 | SIGNING_FAILED | 트랜잭션 서명 실패 |

## Key Design Decisions

### Transactional Outbox Pattern

Payment 저장과 OutboxEvent 저장이 동일 트랜잭션에서 수행된다. 스케줄러가 미발행 이벤트를 Kafka로 릴레이하여 DB-Kafka 간 데이터 일관성을 보장한다.

### Double Locking

- **Redisson 분산 락**: 동일 사용자의 동시 결제 요청 직렬화
- **JPA @Version 낙관적 락**: DB 레벨 동시성 제어

### Kafka At-Least-Once Delivery

Python 컨슈머는 수동 커밋을 사용한다:
- `ValueError` (비재시도 가능) → 커밋 후 스킵
- 기타 `Exception` (재시도 가능) → 커밋하지 않아 재전달

### KMS Envelope Encryption

AWS KMS에서 데이터 암호화 키(DEK)를 생성하고, DEK로 AES-256-GCM 암호화를 수행한다. DEK 자체는 KMS 마스터 키로 암호화하여 저장한다. 평문 DEK는 사용 후 `Arrays.fill((byte) 0)` (Java) / `bytearray` zeroing (Python)으로 메모리에서 소거한다.

### ECDSA Transaction Signing

결제 완료 시 `SHA256withECDSA`로 정규화된 데이터(`ref|sender|receiver|amount|currency`)를 서명하여 트랜잭션 무결성을 보장한다. 서명키는 KMS 봉투 암호화로 보호된다.

## Database Schema

8개 테이블:

| Table | Owner | Purpose |
|-------|-------|---------|
| `payment` | Java | 결제 트랜잭션 |
| `user_balance` | Java | 사용자 잔액 (낙관적 락) |
| `outbox_event` | Java | Transactional Outbox |
| `signing_key` | Java | KMS 암호화 서명키 |
| `merchant_fee_policy` | Python | 가맹점 수수료 정책 |
| `settlement_detail` | Python | 정산 상세 |
| `dw_settlement_summary` | Python | DW 일별 집계 |
| `merchant_sensitive_data` | Python | 가맹점 민감정보 (암호화) |

## Configuration

### Java (application.yml)

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `spring.datasource.url` | `DB_URL` | localhost:3306 | MySQL 접속 URL |
| `spring.datasource.username` | `DB_USERNAME` | payment | DB 사용자 |
| `spring.datasource.password` | `DB_PASSWORD` | payment | DB 비밀번호 |
| `jwt.secret` | `JWT_SECRET` | (required) | JWT 서명 시크릿 |
| `kms.enabled` | `KMS_ENABLED` | false | KMS 활성화 여부 |
| `kms.region` | `AWS_REGION` | ap-northeast-2 | AWS 리전 |
| `kms.key-id` | `AWS_KMS_KEY_ID` | alias/stable-settlement-key | KMS 키 ID |
| `kms.endpoint-override` | `AWS_KMS_ENDPOINT` | (empty) | LocalStack 엔드포인트 |

### Python (config/settings.py)

| Env Var | Default | Description |
|---------|---------|-------------|
| `DB_URL` | (required) | SQLAlchemy DB URL |
| `KAFKA_BOOTSTRAP_SERVERS` | kafka:29092 | Kafka 브로커 |
| `KAFKA_GROUP_ID` | settlement-processor-group | 컨슈머 그룹 |
| `KAFKA_TOPIC` | payment-events | 토픽 이름 |
| `KMS_ENABLED` | false | KMS 활성화 |
| `AWS_REGION` | ap-northeast-2 | AWS 리전 |
| `AWS_KMS_KEY_ID` | alias/stable-settlement-key | KMS 키 ID |
| `AWS_ENDPOINT_URL` | (empty) | LocalStack URL |

## Testing

### Java Unit Tests (37 tests)

```bash
./gradlew :payment-service-java:test \
  --tests "com.payment.stablesettlement.domain.*" \
  --tests "com.payment.stablesettlement.application.*" \
  --tests "com.payment.stablesettlement.infrastructure.kms.*"
```

### Java Integration Tests (Docker 필요)

```bash
./gradlew :payment-service-java:test
```

Testcontainers를 사용하여 MySQL, Redis, Kafka를 자동으로 띄운다.

### Python Tests (39 tests, 87%+ coverage)

```bash
cd settlement-processor-python
source .venv/bin/activate
pytest tests/ -v --cov=src --cov=config --cov-report=term-missing
```

| Test File | Tests | Coverage Target |
|-----------|-------|-----------------|
| test_settlement_engine.py | 11 | 수수료 계산 (PERCENTAGE, FIXED, min/max) |
| test_data_cleanser.py | 11 | DW 집계 (생성, 누적, FOR UPDATE) |
| test_consumer.py | 8 | Kafka 메시지 처리, 커밋 정책 |
| test_kms_encryptor.py | 5 | AES-256-GCM 암호화/복호화 |
| test_merchant_data_service.py | 4 | 가맹점 데이터 암호화 저장/조회 |

## Docker Services

```bash
docker compose up -d          # 전체 기동
docker compose logs -f kafka  # Kafka 로그 확인
docker compose down -v        # 전체 종료 + 볼륨 삭제
```

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| mysql | mysql:8.0 | 3306 | Primary database |
| redis | redis:7.4-alpine | 6379 | Distributed lock, Rate limiting |
| kafka | apache/kafka:3.8.1 | 9092 | Message broker (KRaft) |
| localstack | localstack:3.6 | 4566 | AWS KMS emulation |
| settlement-processor | (custom build) | - | Python 정산 프로세서 |

## Project Phases

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 1 | 핵심 결제 플로우 (Payment, Balance, Outbox) | Completed |
| Phase 2 | 보안 강화 (JWT, Rate Limiting, Actuator) | Completed |
| Phase 3 | Python 정산 프로세서 (수수료 계산, DW 집계) | Completed |
| Phase 4 | KMS 봉투 암호화 + ECDSA 트랜잭션 서명 | Completed |
