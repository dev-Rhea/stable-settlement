# Testing Requirements

## Minimum Test Coverage: 80% (Java & Python)
- Java: JUnit 5, AssertJ, Mockito, Testcontainers.
- Python: Pytest, Pytest-asyncio, Decimal validation tests.

Test Types (ALL required):
1. Unit Tests: Focus on individual business logic in @Service layers and utility classes.
2. Integration Tests
    - Persistence: Use @DataJpaTest with Testcontainers (PostgreSQL) to verify repository queries.
    - API: Use MockMVC or WebTestClient to verify @Controller contracts.
    - Messaging: Use Spring Kafka Test to verify producers/consumers.
3. Cross-Language Contract Tests: Java에서 정의한 PaymentEvent JSON 스키마를 Python에서 Pydantic으로 파싱했을 때 오류가 없는지 검증하는 테스트 필수.
4. E2E Tests: 사용자의 결제 요청부터 Python 정산 테이블에 데이터가 쌓이기까지의 전체 Flow 검증.

## Test-Driven Development

MANDATORY workflow:
1. RED: Write a failing test case in src/test/java.
2. FAIL: Execute the test. It must fail (often with a compilation error or AssertionError).
3. GREEN: Write the minimal code in src/main/java to make the test pass.
4. PASS: Ensure all tests pass.
5. REFACTOR: Clean up code and optimize queries (check for N+1 issues).
6. VERIFY: Check Jacoco coverage reports to ensure 80%+ coverage.

## Troubleshooting Test Failures

1. Agent Support: Invoke the tdd-guide agent immediately.
2. Isolation: Ensure @SpringBootTest is not loading the entire context unnecessarily; use sliced tests (@WebMvcTest, etc.) for speed.
3. Environment: Verify that Docker is running if the test requires Testcontainers for PostgreSQL or Kafka.
4. Mock Integrity: Ensure @MockBean is used correctly to isolate external API dependencies (like Kakao API).
5. Fix Logic, Not Tests: Do not modify assertions to match buggy code.
6. Testcontainers: Docker가 실행 중이어야 하며, Java와 Python 테스트 모두에서 컨테이너 기반 인프라를 활용합니다.
7. Async Testing (Java): Awaitility.await().untilAsserted(...)를 사용하여 비동기 발행 로직을 검증합니다.

## Agent Support

- tdd-guide: Java의 BigDecimal과 Python의 Decimal 사용 여부를 테스트 코드 수준에서 강제합니다.
- e2e-runner: Specialized in cross-service flow validation and UI testing for the settlement detail pages.
- contract-checker: Ensures that changes in one service do not break the API expectations of another.
- contract-checker: Java의 DTO 변경이 Python의 Pydantic 모델과 충돌하는지 감지합니다.