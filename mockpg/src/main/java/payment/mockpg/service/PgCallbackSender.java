package payment.mockpg.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import payment.mockpg.domain.PgTransaction;
import payment.mockpg.dto.PgCallbackPayload;
import payment.mockpg.dto.PgResult;

@Slf4j
@Component
public class PgCallbackSender {

    private final RestClient restClient;
    private final String callbackUrl;


    public PgCallbackSender(@Value("${payment-service.callback-url}") String callbackUrl) {
        this.restClient = RestClient.create();
        this.callbackUrl = callbackUrl;
    }

    public void sendCallback(PgTransaction transaction, boolean approved) {
        PgCallbackPayload payload = new PgCallbackPayload(
            transaction.getPgTransactionId(),
            approved ? PgResult.APPROVED : PgResult.REJECTED,
            approved ? null : "Mock PG declined the transaction"
        );

        try {
            restClient.post()
                .uri(callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
            log.info("Callback sent: pgTransactionId = {}, result = {}", transaction.getPgTransactionId(), payload.result());
        } catch (RestClientException e) {
            log.error("Failed to send PG callback: pgTransactionId = {}", transaction.getPgTransactionId(), e);
        }
    }
}
