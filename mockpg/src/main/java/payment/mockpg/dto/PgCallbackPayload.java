package payment.mockpg.dto;

public record PgCallbackPayload(
    String pgTransactionId, PgResult result, String reason
) {

}
