package com.lifeos.gateway.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code lifeos.gateway.circuit-breaker.*}: defaults for every route's circuit breaker.
 *
 * @param slidingWindowSize              number of recent calls evaluated
 * @param minimumNumberOfCalls           calls needed before the breaker can open
 * @param failureRateThreshold           percentage of failed calls that opens the breaker
 * @param waitInOpenState                how long an open breaker fails fast before probing again
 * @param permittedCallsInHalfOpenState  probe calls allowed while half-open
 * @param timeLimit                      upper bound per call; keep it above the HTTP response timeout
 */
@ConfigurationProperties("lifeos.gateway.circuit-breaker")
public record CircuitBreakerProperties(
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("10") int minimumNumberOfCalls,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10s") Duration waitInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpenState,
        @DefaultValue("12s") Duration timeLimit) {
}
