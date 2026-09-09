# Stable Settlement

Stablecoin 기반 실시간 결제 및 정산 시스템. Spring Boot 결제 서비스가 외부 PG의 비동기 승인을 거쳐 트랜잭션을 처리하고, Kafka를 통해 Python 정산 프로세서로 이벤트를 전달하여 수수료 계산 및 DW 집계를 수행한다. 그리고 내부 원장과 PG 외부 원장의 불일치를 Spring Batch 대사(Reconciliation) 배치가 매일 검증한다.

## Architecture

```
                          ┌─────────────────┐
                          │     Client       │
                          └────────┬────────┘
                                   │ REST API (JWT)
                          ┌────────▼─────────┐   승인 요청/재조회   ┌──────────────┐
                          │  Payment Service  │ ─────────────────▶ │  Mock PG      │
                          │      :8080        │ ◀───────────────── │  Server :8081 │
                          └──┬────┬────┬─────┘   비동기 콜백        └───────┬──────┘
                             │    │    │                                   │ 일일 거래 CSV
                    ┌────────┘    │    └────────┐                          │
                    ▼             ▼             ▼                          │
              ┌──────────┐ ┌──────────┐  ┌───────────┐                     │
              │  MySQL   │ │  Redis   │  │   Kafka   │                     │
              │  :3306   │ │  :6379   │  │   :9092   │                     │
              └────┬─────┘ └──────────┘  └─────┬─────┘                     │
                   │                           │ payment-events            │
                   │                     ┌─────▼──────────┐                │
                   │                     │   Settlement    │ ← Python 3.11+ │
                   │                     │   Processor     │                │
                   │                     └──┬──────────┬──┘                │
                   │                        │          │                    │
                   │                   ┌────▼───┐ ┌───▼────────┐            │
                   │                   │ MySQL  │ │ LocalStack │            │
                   │                   │        │ │ KMS :4566  │            │
                   │                   └────────┘ └────────────┘            │
                   │                                                        │
            ┌──────▼───────────────┐                                        │
            │ Reconciliation Batch  │ ◀──────────────────────────────────────┘
            │ (Spring Batch, 일 1회) │ ──▶ Kafka: RECONCILIATION_MISMATCH_DETECTED
            └───────────────────────┘
```

## Tech Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Payment Service | Spring Boot | 3.5.10 |
| Mock PG Server | Spring Boot | 3.5.10 |
| Reconciliation Batch | Spring Boot + Spring Batch | 3.5.10 / 5.2.x |
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
├── payment-service-java/          # Spring Boot 결제 서비스 (:8080)
│   └── src/main/java/.../
│       ├── domain/                # 엔티티, 레포지토리
│       │   ├── payment/           #   Payment, PaymentStatus (PENDING/COMPLETED/FAILED/TIMEOUT)
│       │   ├── hold/              #   BalanceHold, HoldStatus (가예약)
│       │   ├── user/              #   UserBalance (@Version 낙관적 락 + heldAmount)
│       │   ├── outbox/            #   OutboxEvent (Transactional Outbox)
│       │   └── signing/           #   SigningKey (KMS 암호화 서명키)
│       ├── application/           # 비즈니스 로직
│       │   ├── service/           #   PaymentService, PaymentHoldService,
│       │   │                      #   PgCallbackService, PaymentTimeoutService,
│       │   │                      #   PaymentOutboxRecorder, TransactionSigner
│       │   └── exception/         #   도메인 예외
│       ├── infrastructure/        # 외부 시스템 연동
│       │   ├── config/            #   Clock, Kafka, RateLimit, Redisson, PgClient
│       │   ├── kafka/             #   Outbox Relayer/Publisher/Cleaner
│       │   ├── kms/               #   KmsConfig, Envelope Encryptor, NoOp
│       │   ├── pg/                #   MockPgClient, PgPendingReconciler(타임아웃 재조회)
│       │   └── security/          #   JWT, SecurityConfig
│       └── interfaces/            # API 레이어
│           ├── controller/        #   REST 컨트롤러 (Payment, Balance, PgCallback)
│           └── dto/               #   Request/Response Records
│
├── mockpg/                        # Mock PG 서버 (:8081)
│   └── src/main/java/payment/mockpg/
│       ├── controller/            #   승인 요청, 상태 재조회, 일일 거래 CSV export
│       ├── service/               #   PgApprovalService, PgCallbackSender, PgSettlementExporter
│       ├── store/                 #   InMemoryPgTransactionStore
│       └── domain/                #   PgTransaction, PgTransactionStatus
│
├── reconciliation-batch/          # Spring Batch 대사 배치
│   └── src/main/java/com/payment/reconciliation/
│       ├── domain/                #   LedgerEntry, ReconciliationResult, ReconciliationStatus
│       ├── engine/                #   ReconciliationMatcher (판정 로직)
│       ├── pg/                    #   PgLedgerSource, MockPgLedgerSource, CSV 파서
│       └── job/                   #   Job/Step 구성, 파티셔너, 불일치 알림
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
└── docker-compose.yml             # MySQL, Redis, Kafka, LocalStack, 서비스들
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

MySQL, Redis, Kafka, LocalStack(KMS), Payment Service, Mock PG Server, Settlement Processor가 기동된다.
대사 배치는 상시 서비스가 아니므로 `batch` 프로필로 분리되어 있다.

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

### 3. Mock PG Server (Java)

```bash
./gradlew :mockpg:bootRun
```

`:8081`에서 기동한다. 승인 요청을 받으면 0.5~5초 랜덤 지연 후 90% 확률로 승인하고,
결제 서비스의 `/internal/pg-callback`으로 비동기 콜백을 보낸다.

### 4. Settlement Processor (Python)

Docker Compose로 자동 실행되지만, 로컬에서 직접 실행하려면:

```bash
cd settlement-processor-python
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

DB_URL=mysql+pymysql://payment:payment@localhost:3306/stable_settlement \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
python -m src.main
```

### 5. Reconciliation Batch (Java)

대사 대상 날짜를 Job 파라미터로 넘겨 실행한다.

```bash
DB_URL=jdbc:mysql://localhost:3306/stable_settlement?useSSL=false&allowPublicKeyRetrieval=true \
DB_USERNAME=payment \
DB_PASSWORD=payment \
./gradlew :reconciliation-batch:bootRun \
  --args='--spring.batch.job.name=dailyReconciliationJob --reconciliationDate=2025-01-15'
```

대용량 구간을 병렬 처리하려면 `--spring.batch.job.name=partitionedReconciliationJob`을 쓴다.
Docker Compose로 돌릴 때는 다음과 같다.

```bash
docker compose --profile batch run --rm reconciliation-batch --reconciliationDate=2025-01-15
```

## API Reference

### Authentication

> `POST /api/v1/auth/token` 은 dev/test 프로필에서만 활성화된다.

```bash
# JWT 토큰 발급
curl -X POST "http://localhost:8080/api/v1/auth/token?userId=1"
```

### Payment

결제는 PG 승인을 기다려야 하므로 **즉시 확정되지 않는다**. 요청은 `202 Accepted`와 `PENDING`으로 응답하고,
최종 결과는 조회 API로 폴링한다.

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

**Response** `202 Accepted`

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
    "status": "PENDING",
    "signed": false,
    "pgTransactionId": "PGTX-3f0c...",
    "createdAt": "2025-01-15T10:00:00"
  }
}
```

```bash
# 결제 상태 폴링 (PG 콜백 도착 후 COMPLETED / FAILED / TIMEOUT)
curl http://localhost:8080/api/v1/payments/PAY-001 \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### Balance

```bash
# 잔액 조회 (balance, heldAmount, availableBalance)
curl http://localhost:8080/api/v1/balances/1 \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

### PG Callback (internal)

Mock PG가 호출하는 내부 엔드포인트다.

```bash
curl -X POST http://localhost:8080/internal/pg-callback \
  -H "Content-Type: application/json" \
  -d '{"pgTransactionId": "PGTX-3f0c...", "result": "APPROVED"}'
```

### Mock PG Server

| Method | Path | Description |
|--------|------|-------------|
| POST | `/pg/approve` | 승인 요청 (202, `pgTransactionId` 반환) |
| GET | `/pg/transactions/{pgTransactionId}` | 승인 결과 재조회 (콜백 유실 복구용) |
| GET | `/pg/settlements/{date}` | 일일 거래 내역 CSV (대사 배치의 외부 원장) |

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

### Error Responses

| Status | Code | Description |
|--------|------|-------------|
| 400 | CURRENCY_MISMATCH | 통화 불일치 |
| 400 | INSUFFICIENT_BALANCE | 가용 잔액 부족 |
| 400 | SELF_PAYMENT | 자기 자신에게 송금 |
| 403 | - | 타인의 결제/잔액 접근 |
| 404 | SENDER_NOT_FOUND | 송신자 미존재 |
| 404 | RECEIVER_NOT_FOUND | 수신자 미존재 |
| 409 | DUPLICATE_PAYMENT | 중복 결제 / 낙관적 락 충돌 |
| 429 | - | Rate limit 초과 (10 req/min) |
| 500 | PG_REQUEST_FAILED | Mock PG 승인 요청 실패 (가예약 자동 해제) |
| 500 | SIGNING_FAILED | 트랜잭션 서명 실패 |

## Key Design Decisions

### 가예약(Hold) → 확정(Capture) 2단계 잔액 모델

결제 요청 시점에는 잔액을 차감하지 않고 `heldAmount`로 가예약만 한다. PG 승인 콜백이 도착하면
확정 차감(`captureHold`)하고, 거절이면 가예약만 해제(`releaseHold`)한다. 가용 잔액은
`balance - heldAmount`로 계산되므로, 승인 대기 중에도 후속 결제가 가예약분을 정확히 반영해 차단된다.

### 콜백 유실 대비 재조회 + 타임아웃

콜백은 유실될 수 있다. `PgPendingReconciler`가 주기적으로 PENDING 상태가 오래된 결제를 찾아
Mock PG에 재조회한다. 결과가 나와 있으면 콜백과 **같은 경로**(`PgCallbackService`)로 확정해
락과 멱등 처리를 그대로 재사용하고, 끝내 응답이 없으면 `TIMEOUT`으로 확정하며 가예약을 해제한다.

### Transactional Outbox Pattern

결제 상태 변경과 OutboxEvent 저장이 동일 트랜잭션에서 수행된다. 스케줄러가 미발행 이벤트를
Kafka로 릴레이하여 DB-Kafka 간 데이터 일관성을 보장한다. 이벤트는 `PAYMENT_PENDING` /
`PAYMENT_COMPLETED` / `PAYMENT_FAILED` / `PAYMENT_TIMEOUT`으로 세분화되고, Kafka 레코드의
`event-type` 헤더에도 실려 구독자가 페이로드를 파싱하지 않고 분기할 수 있다.

### Double Locking

- **Redisson 분산 락**: 동일 사용자의 동시 결제 요청 직렬화. 결제 요청과 PG 콜백이 같은
  락 키(`payment:lock:{senderId}`)를 쓰기 때문에 콜백 처리도 함께 직렬화된다.
- **JPA @Version 낙관적 락**: DB 레벨 동시성 제어

### 콜백 멱등성

동일 `pgTransactionId`로 콜백이 중복 도착해도, 결제가 이미 종단 상태면 아무것도 바꾸지 않고
`duplicate` 메트릭만 올린 뒤 반환한다.

### Kafka At-Least-Once Delivery

Python 컨슈머는 수동 커밋을 사용한다:
- `ValueError` (비재시도 가능) → 커밋 후 스킵
- 기타 `Exception` (재시도 가능) → 커밋하지 않아 재전달

### 일일 대사(Reconciliation) 배치

실시간 동시성 제어만으로는 외부 PG와의 경계에서 생기는 불일치를 발견할 수 없다.
Spring Batch Job이 매일 내부 원장(`payment`)과 외부 원장(Mock PG 일일 CSV)을
`payment_reference` 기준으로 전수 대조한다.

```
loadExternalLedger → buildWorkItems → reconcile → notifyMismatch
```

| 판정 | 의미 |
|------|------|
| `MATCHED` | 양쪽 존재, 금액 일치 |
| `INTERNAL_ONLY` | 내부에만 존재 — PG 응답 유실 가능성 |
| `EXTERNAL_ONLY` | 외부에만 존재 — 실제로 돈이 오갔는데 내부 기록 없음 (가장 심각) |
| `AMOUNT_MISMATCH` | 양쪽 존재, 금액 불일치 |

- **재실행 멱등성**: 앞 두 Step이 해당 날짜의 산출물을 먼저 지우므로 같은 날짜로 몇 번 돌려도 결과가 중복되지 않는다.
- **재시작 의미론**: 동일 `JobParameters`로 재실행하면 완료된 Step은 건너뛰고 실패한 Step부터 재개된다.
- **파티셔닝**: `partitionedReconciliationJob`은 대사 Step을 작업 항목 ID 구간으로 쪼개 워커 스레드로 병렬 처리한다. 단일 스레드 Job과 나란히 두어 처리 시간을 비교할 수 있다.
- **알림**: `MATCHED`가 아닌 모든 결과를 `RECONCILIATION_MISMATCH_DETECTED` 이벤트로 Kafka에 발행한다.

### KMS Envelope Encryption

AWS KMS에서 데이터 암호화 키(DEK)를 생성하고, DEK로 AES-256-GCM 암호화를 수행한다. DEK 자체는 KMS 마스터 키로 암호화하여 저장한다. 평문 DEK는 사용 후 `Arrays.fill((byte) 0)` (Java) / `bytearray` zeroing (Python)으로 메모리에서 소거한다.

### ECDSA Transaction Signing

결제 완료 시 `SHA256withECDSA`로 정규화된 데이터(`ref|sender|receiver|amount|currency`)를 서명하여 트랜잭션 무결성을 보장한다. 서명키는 KMS 봉투 암호화로 보호된다.

## Database Schema

| Table | Owner | Purpose |
|-------|-------|---------|
| `payment` | Java | 결제 트랜잭션 (PG 거래 ID, 요청 시각 포함) |
| `user_balance` | Java | 사용자 잔액 (낙관적 락, `held_amount`) |
| `balance_hold` | Java | 가예약 원장 (HELD / CAPTURED / RELEASED) |
| `outbox_event` | Java | Transactional Outbox |
| `signing_key` | Java | KMS 암호화 서명키 |
| `merchant_fee_policy` | Python | 가맹점 수수료 정책 |
| `settlement_detail` | Python | 정산 상세 |
| `dw_settlement_summary` | Python | DW 일별 집계 |
| `merchant_sensitive_data` | Python | 가맹점 민감정보 (암호화) |
| `external_ledger_entry` | Batch | PG 일일 거래 내역 적재본 (외부 원장) |
| `reconciliation_work_item` | Batch | 내부/외부 원장을 합친 대사 작업 항목 |
| `reconciliation_result` | Batch | 대사 판정 결과 |

## Configuration

### Payment Service (application.yml)

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
| `mock-pg.base-url` | `MOCK_PG_BASE_URL` | http://localhost:8081 | Mock PG 주소 |
| `pg.reconcile.fixed-delay-ms` | - | 10000 | 재조회 스케줄러 주기 |
| `pg.reconcile.inquiry-after-seconds` | - | 30 | 이 시간 이상 PENDING이면 재조회 |
| `pg.reconcile.give-up-after-seconds` | - | 300 | 이 시간 이상 무응답이면 TIMEOUT |

### Mock PG Server (application.yml)

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `mock-pg.approval-delay.min-ms` | - | 500 | 최소 승인 지연 |
| `mock-pg.approval-delay.max-ms` | - | 5000 | 최대 승인 지연 |
| `mock-pg.approval-probability` | - | 0.9 | 승인 확률 |
| `payment-service.callback-url` | `PAYMENT_SERVICE_CALLBACK_URL` | http://localhost:8080/internal/pg-callback | 콜백 대상 |

### Reconciliation Batch (application.yml)

| Property | Env Var | Default | Description |
|----------|---------|---------|-------------|
| `spring.batch.job.name` | `BATCH_JOB_NAME` | dailyReconciliationJob | 실행할 Job |
| `mock-pg.base-url` | `MOCK_PG_BASE_URL` | http://localhost:8081 | 외부 원장 소스 |
| `reconciliation.notify.kafka-enabled` | `RECONCILIATION_KAFKA_ENABLED` | true | 불일치 알림 발행 여부 |
| `reconciliation.notify.topic` | `RECONCILIATION_TOPIC` | reconciliation-events | 알림 토픽 |

Job 파라미터로 `--reconciliationDate=YYYY-MM-DD` 가 필요하다.

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

### Java Unit Tests (103 tests)

Docker 없이 도는 단위 테스트다.

```bash
# payment-service (81 tests)
./gradlew :payment-service-java:test \
  --tests "com.payment.stablesettlement.domain.*" \
  --tests "com.payment.stablesettlement.application.*" \
  --tests "com.payment.stablesettlement.infrastructure.*"

# mock-pg (6 tests)
./gradlew :mockpg:test

# reconciliation-batch (19 tests, H2 인메모리에서 Job 전체 실행)
./gradlew :reconciliation-batch:test
```

`reconciliation-batch` 테스트는 H2로 Job을 끝까지 돌려 4가지 판정, 재실행 멱등성,
실패 Step부터의 재시작, 파티셔닝 결과의 무결성까지 검증한다.

### Java Integration Tests (Docker 필요)

```bash
./gradlew :payment-service-java:test
```

Testcontainers를 사용하여 MySQL, Redis, Kafka를 자동으로 띄운다. PG는 스텁 빈으로 대체하고
콜백 엔드포인트를 직접 호출해 승인/거절/중복 콜백 시나리오를 재현한다.

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
docker compose up -d          # 상시 서비스 기동
docker compose logs -f kafka  # Kafka 로그 확인
docker compose down -v        # 전체 종료 + 볼륨 삭제

# 대사 배치 1회 실행
docker compose --profile batch run --rm reconciliation-batch --reconciliationDate=2025-01-15
```

| Service | Image | Port | Purpose |
|---------|-------|------|---------|
| mysql | mysql:8.0 | 3306 | Primary database |
| redis | redis:7.4-alpine | 6379 | Distributed lock, Rate limiting |
| kafka | apache/kafka:3.8.1 | 9092 | Message broker (KRaft) |
| localstack | localstack:3.6 | 4566 | AWS KMS emulation |
| payment-service | (custom build) | 8080 | 결제 서비스 |
| mock-pg-server | (custom build) | 8081 | Mock PG 서버 |
| settlement-processor | (custom build) | - | Python 정산 프로세서 |
| reconciliation-batch | (custom build) | - | 대사 배치 (`batch` 프로필, 1회성) |

## Project Phases

| Phase | Description | Status |
|-------|-------------|--------|
| Phase 1 | 핵심 결제 플로우 (Payment, Balance, Outbox) | Completed |
| Phase 2 | 보안 강화 (JWT, Rate Limiting, Actuator) | Completed |
| Phase 3 | Python 정산 프로세서 (수수료 계산, DW 집계) | Completed |
| Phase 4 | KMS 봉투 암호화 + ECDSA 트랜잭션 서명 | Completed |
| Phase 5 | PG 비동기 승인 (가예약-확정, 콜백, 재조회, TIMEOUT) | Completed |
| Phase 6 | 대사(Reconciliation) 배치 + 불일치 알림 | Completed |
