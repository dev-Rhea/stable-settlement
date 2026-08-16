# Security Guidelines

## Mandatory Security Checks

Before ANY commit:
- [ ] No hardcoded secrets (Use environment variables: API keys, passwords, tokens)
- [ ] All user inputs validated
- [ ] SQL injection prevention (Use JPA/SQLAlchemy parameter binding)
- [ ] XSS prevention (sanitized HTML)
- [ ] CSRF protection enabled
- [ ] Authentication/authorization verified
- [ ] Rate limiting on all endpoints
- [ ] Error messages don't leak sensitive data
- [ ] Input validation (Strict type checking via Pydantic/Spring Validation)
- [ ] Log Sanitization: Mask PII (Personal Identifiable Information) in logs.
- [ ] Idempotency Check: Verify payment_id uniqueness in settlement processing.
- [ ] Precision Check: Ensure NO float/double is used for money calculations.

## Secret Management

```Java
// ❌ NEVER: 소스 코드에 직접 기입
private String apiKey = "sk-proj-xxxxx";

// ✅ ALWAYS: application.yml 환경변수 주입 및 검증
@Component
public class ApiConfig {
  @Value("${openai.api.key}")
  private String apiKey;

  @PostConstruct
  public void validate() {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("OPENAI_API_KEY must be configured in environment variables.");
    }
  }
}
```
```Python
# ✅ ALWAYS: Python (FastAPI/Pydantic-Settings)
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    kafka_broker: str
    db_url: str
    api_secret_key: str

    class Config:
        env_file = ".env"

settings = Settings()
# validate()는 Pydantic에 의해 인스턴스화 시 자동 수행됨
```

## Security Response Protocol

If security issue found:
1. STOP immediately
2. Use **security-reviewer** agent
3. Fix CRITICAL issues before continuing
4. Rotate any exposed secrets
5. Review entire codebase for similar issues

## Distributed Security Protocol
1. At-Least-Once Delivery Handling: Java에서 발행한 이벤트가 Python에서 중복 소비될 수 있음을 가정하고 반드시 멱등성(Idempotency) 로직을 구현합니다.
2. Secure Communication: 인프라 구성 시 Kafka 및 DB 통신에 가능하면 TLS/SSL을 적용합니다.
3. Data Integrity: 결제 원장(Java)과 정산 이력(Python) 간의 금액 합계가 일치하는지 주기적으로 대조하는 감사(Audit) 로그를 남깁니다.