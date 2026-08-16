package payment.mockpg.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "mock-pg")
public class MockProperties {

    private final ApprovalDelay approvalDelay = new ApprovalDelay();
    private double approvalProbability = 0.9;

    @Getter
    @Setter
    public static class ApprovalDelay {
        private long minMs = 500;
        private long maxMs = 5000;
    }
}
