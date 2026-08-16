package com.payment.stablesettlement.interfaces.dto.pg;

import java.math.BigDecimal;

public record PgApprovalRequest(
    String paymentReference,
    BigDecimal amount,
    String currency
) {

}
