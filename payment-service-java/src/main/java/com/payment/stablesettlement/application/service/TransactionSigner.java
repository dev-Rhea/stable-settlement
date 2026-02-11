package com.payment.stablesettlement.application.service;

import com.payment.stablesettlement.domain.payment.Payment;

public interface TransactionSigner {

    String signTransaction(Payment payment);
}
