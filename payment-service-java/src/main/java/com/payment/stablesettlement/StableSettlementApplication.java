package com.payment.stablesettlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StableSettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(StableSettlementApplication.class, args);
    }

}
