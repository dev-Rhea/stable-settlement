package com.payment.stablesettlement.infrastructure.kms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "kms.enabled", havingValue = "true")
public class KmsConfig {

    @Bean
    public KmsClient kmsClient(
            @Value("${kms.region}") String region,
            @Value("${kms.endpoint-override:}") String endpointOverride) {

        var builder = KmsClient.builder()
                .region(Region.of(region));

        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride));
        }

        return builder.build();
    }
}
