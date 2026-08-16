# Common Patterns

## Cross-Language API Standard (Java & Python)

```Java
@Getter
@Builder
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final ApiError error;
    private final Meta meta;

    @Getter
    @AllArgsConstructor
    public static class ApiError {
        private final String code;
        private final String message;
    }

    @Getter
    @Builder
    public static class Meta {
        private final long total;
        private final int page;
        private final int limit;
    }
}
```
```Python
class ApiResponse(GenericModel, Generic[T]):
    success: bool
    data: Optional[T]
    error: Optional[ApiError]
    meta: Optional[Meta]

    class Config:
        alias_generator = to_snake # JSON 출력 시 snake_case 강제
```

## Service Layer Pattern

```Java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementService {
  private final SettlementRepository repository;
  private final RedisDistributedLock lock;

  @Transactional
  public SettlementResponse createSettlement(SettlementRequest request) {
  // Business Logic
  return SettlementResponse.from(repository.save(request.toEntity()));
}
}
```

## Repository Pattern (Spring Data JPA)

```Java
public interface GenericRepository<T, ID> extends JpaRepository<T, ID> {
    // Basic CRUD is inherited from JpaRepository

    // Custom query methods using Method Name Convention or @Query
    Optional<T> findByActiveTrue();
}

public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Settlement s WHERE s.id = :id")
    Optional<Settlement> findByIdWithLock(@Param("id") Long id);
}
```
## Outbox Service Pattern (Java Specific)
```Java
@Transactional
public PaymentResponse processPayment(PaymentRequest request) {
    // 1. 비즈니스 로직 처리 (잔액 차감 등)
    Payment payment = paymentRepository.save(request.toEntity());
    
    // 2. 동일 트랜잭션 내 Outbox 기록
    paymentOutboxRepository.save(PaymentEvent.from(payment));
    
    return PaymentResponse.from(payment);
}
```
## Idempotent Consumer Pattern (Python Specific)
```Python
def process_settlement(event):
    # 1. payment_id로 이미 정산되었는지 확인 (Unique Constraint 활용)
    if settlement_repo.exists_by_payment_id(event.payment_id):
        return  # 이미 처리된 경우 무시
    
    # 2. 정산 계산 및 저장
    settlement = calculate_fee(event)
    settlement_repo.save(settlement)
```
## Skeleton Projects & Module Implementation

This outlines the standard procedure when implementing new features within the project:

1. MSA Module-Based Discovery: Prioritize searching for battle-tested logic (e.g., payment, settlement) within the existing common module or similar service modules before creating new implementations.
2. Multi-Agent Analysis: Utilize specialized analysis to ensure high standards:
    - Security Review: Does the code comply with security-reviewer.md? (e.g., Use of BigDecimal for currency, input sanitization).
    - Architectural Fit: Does it align with the Saga or Transactional Outbox patterns defined in architect.md?
    - Efficiency: Are queries optimized to prevent N+1 problems?
    - Schema Consistency: Java Producer가 발행하는 JSON 필드와 Python Consumer가 읽는 필드가 일치하는가?
    - Decimal Handling: 양측 언어 모두 금액 계산 시 BigDecimal(Java)과 Decimal(Python)을 사용하는가?
3. Code Scaffolding: Verify build.gradle dependencies and replicate the established Controller-Service-Repository layer structure to maintain architectural consistency.
4. Iterative Refinement: Ensure the integrity of business logic through comprehensive unit testing (JUnit 5) and refine the implementation based on test results.