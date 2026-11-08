package com.lifeos.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies {@link CircuitBreakerProperties} to every route's breaker. A breaker counts exceptions (connection
 * refused, timeouts), not HTTP error statuses: a service answering 4xx/5xx is up and its answer is passed on.
 * Configured in code so the values do not depend on which Resilience4j Spring Boot module is on the classpath.
 */
@Configuration
@EnableConfigurationProperties(CircuitBreakerProperties.class)
public class CircuitBreakerConfig {

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreaker(CircuitBreakerProperties p) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowType(SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(p.slidingWindowSize())
                        .minimumNumberOfCalls(p.minimumNumberOfCalls())
                        .failureRateThreshold(p.failureRateThreshold())
                        .waitDurationInOpenState(p.waitInOpenState())
                        .permittedNumberOfCallsInHalfOpenState(p.permittedCallsInHalfOpenState())
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(p.timeLimit()).build())
                .build());
    }
}
