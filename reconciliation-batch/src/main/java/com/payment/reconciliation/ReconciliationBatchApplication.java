package com.payment.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReconciliationBatchApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(
            SpringApplication.run(ReconciliationBatchApplication.class, args)));
    }
}
