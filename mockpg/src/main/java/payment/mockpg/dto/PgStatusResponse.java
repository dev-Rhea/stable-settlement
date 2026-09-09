package payment.mockpg.dto;

import payment.mockpg.domain.PgTransactionStatus;

public record PgStatusResponse(
    String pgTransactionId,
    String paymentReference,
    PgTransactionStatus status
) {

}
