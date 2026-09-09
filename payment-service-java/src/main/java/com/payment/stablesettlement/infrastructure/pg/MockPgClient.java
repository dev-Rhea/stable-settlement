package com.payment.stablesettlement.infrastructure.pg;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.application.service.PgClient;
import com.payment.stablesettlement.interfaces.dto.pg.PgApprovalRequest;
import com.payment.stablesettlement.interfaces.dto.pg.PgApprovalResponse;
import com.payment.stablesettlement.interfaces.dto.pg.PgResult;
import com.payment.stablesettlement.interfaces.dto.pg.PgStatusResponse;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MockPgClient implements PgClient {

    private final RestClient pgRestClient;

    @Override
    public String requestApproval(String paymentReference, BigDecimal amount, String currency) {
        try {
            PgApprovalResponse response = pgRestClient.post()
                .uri("/pg/approve")
                .body(new PgApprovalRequest(paymentReference, amount, currency))
                .retrieve()
                .body(PgApprovalResponse.class);

            if(response == null || response.pgTransactionId() == null || response.pgTransactionId().isBlank()) {
                throw new PaymentException("PG_REQUEST_FAILED", "Mock PG returned an empty response");
            }

            return response.pgTransactionId();
        } catch (PaymentException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Failed to reach Mock PG for paymentReference = {}", paymentReference, e);
            throw new PaymentException("PG_REQUEST_FAILED", "Failed to reach Mock PG");
        }
    }

    @Override
    public Optional<PgResult> queryResult(String pgTransactionId) {
        try {
            PgStatusResponse response = pgRestClient.get()
                .uri("/pg/transactions/{pgTransactionId}", pgTransactionId)
                .retrieve()
                .body(PgStatusResponse.class);

            if (response == null || response.status() == null) {
                return Optional.empty();
            }

            return switch (response.status()) {
                case "APPROVED" -> Optional.of(PgResult.APPROVED);
                case "REJECTED" -> Optional.of(PgResult.REJECTED);
                default -> Optional.empty();
            };
        } catch (RestClientException e) {
            // 재조회 실패는 스케줄러가 다음 주기에 다시 시도하므로 예외를 전파하지 않는다.
            log.warn("Failed to re-query Mock PG: pgTransactionId = {}, cause = {}",
                pgTransactionId, e.getMessage());
            return Optional.empty();
        }
    }
}
