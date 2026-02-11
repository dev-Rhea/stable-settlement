package com.payment.stablesettlement.infrastructure.kms;

import com.payment.stablesettlement.application.service.TransactionSigner;
import com.payment.stablesettlement.application.service.TransactionSigningService;
import com.payment.stablesettlement.domain.payment.Payment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnMissingBean(TransactionSigningService.class)
public class NoOpTransactionSigner implements TransactionSigner {

    @Override
    public String signTransaction(Payment payment) {
        log.debug("KMS disabled — returning UNSIGNED for payment: {}",
                payment.getPaymentReference());
        return "UNSIGNED";
    }
}
