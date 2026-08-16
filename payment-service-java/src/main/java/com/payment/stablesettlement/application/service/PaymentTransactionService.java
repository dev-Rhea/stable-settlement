package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.domain.payment.Payment;
import com.payment.stablesettlement.interfaces.dto.PaymentRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentHoldService paymentHoldService;
    private final PgClient pgClient;
    private final MeterRegistry meterRegistry;

    public Payment executePayment(PaymentRequest request) {
        Timer.Sample timerSample = Timer.start(meterRegistry);

        try {
            Payment payment = doExecutePayment(request);

            meterRegistry.counter("payment.processed", "status", "success").increment();
            timerSample.stop(meterRegistry.timer("payment.duration", "status", "success"));

            return payment;
        } catch (Exception e) {
            meterRegistry.counter("payment.processed", "status", "failure").increment();
            timerSample.stop(meterRegistry.timer("payment.duration", "status", "failure"));
            throw e;
        }
    }

    private Payment doExecutePayment(PaymentRequest request) {
        Payment payment = paymentHoldService.createHold(request);

        try {
            String pgTransactionId = pgClient.requestApproval(
                payment.getPaymentReference(), payment.getAmount(),
                payment.getCurrency()
            );

            return paymentHoldService.attachPgTransaction(payment.getPaymentReference(), pgTransactionId);
        } catch (Exception e) {
            log.error("PG Initiation failed, releasing hold: reference = {}", payment.getPaymentReference(), e);
            paymentHoldService.releaseOnInitiationFailure(payment.getPaymentReference(), e.getMessage());
            throw e;
        }
    }
}
