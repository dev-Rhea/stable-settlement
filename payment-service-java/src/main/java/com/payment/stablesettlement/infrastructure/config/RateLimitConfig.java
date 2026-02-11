package com.payment.stablesettlement.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RedissonClient;
import org.redisson.command.CommandAsyncExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

@Configuration
public class RateLimitConfig {

    @Value("${rate-limit.capacity}")
    private int capacity;

    @Value("${rate-limit.refill-tokens}")
    private int refillTokens;

    @Value("${rate-limit.refill-duration-minutes}")
    private int refillDurationMinutes;

    @Bean
    public ProxyManager<String> proxyManager(RedissonClient redissonClient) {
        if (!(redissonClient instanceof org.redisson.Redisson redisson)) {
            throw new IllegalStateException(
                    "Rate limiting requires org.redisson.Redisson implementation");
        }
        CommandAsyncExecutor commandExecutor = redisson.getCommandExecutor();
        return RedissonBasedProxyManager.builderFor(commandExecutor)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(refillDurationMinutes * 2L)))
                .build();
    }

    @Bean
    public Supplier<BucketConfiguration> bucketConfigurationSupplier() {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(refillTokens, Duration.ofMinutes(refillDurationMinutes))
                        .build())
                .build();
    }

    @Bean
    public OncePerRequestFilter rateLimitFilter(ProxyManager<String> proxyManager,
                                                 Supplier<BucketConfiguration> bucketConfig,
                                                 ObjectMapper objectMapper) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                String uri = request.getRequestURI().replaceAll(";.*", "").replaceAll("/+$", "");
                if (!"POST".equalsIgnoreCase(request.getMethod()) ||
                        !uri.equals("/api/v1/payments")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth.getPrincipal() == null) {
                    filterChain.doFilter(request, response);
                    return;
                }

                String key = "rate-limit:payment:" + auth.getPrincipal();
                var bucket = proxyManager.builder().build(key, bucketConfig);
                var probe = bucket.tryConsumeAndReturnRemaining(1);

                if (probe.isConsumed()) {
                    response.setHeader("X-Rate-Limit-Remaining",
                            String.valueOf(probe.getRemainingTokens()));
                    filterChain.doFilter(request, response);
                } else {
                    long waitSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000 + 1;
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setHeader("Retry-After", String.valueOf(waitSeconds));
                    response.setHeader("X-Rate-Limit-Remaining", "0");
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            objectMapper.writeValueAsString(
                                    java.util.Map.of("success", false,
                                            "error", "Too many requests, please retry after " + waitSeconds + " seconds")));
                }
            }
        };
    }
}
