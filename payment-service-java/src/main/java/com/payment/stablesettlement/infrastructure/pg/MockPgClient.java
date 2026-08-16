package com.payment.stablesettlement.infrastructure.pg;

import com.payment.stablesettlement.application.exception.PaymentException;
import com.payment.stablesettlement.application.service.PgClient;
import com.payment.stablesettlement.interfaces.dto.pg.PgApprovalRequest;
import com.payment.stablesettlement.interfaces.dto.pg.PgApprovalResponse;
import java.math.BigDecimal;
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
}
