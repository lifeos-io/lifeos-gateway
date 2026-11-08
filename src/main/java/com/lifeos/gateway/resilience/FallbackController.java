package com.lifeos.gateway.resilience;

import com.lifeos.base.error.CommonErrorCode;
import com.lifeos.gateway.error.GatewayErrorCode;
import com.lifeos.gateway.error.ProblemWriter;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Target of every route's {@code fallbackUri}: turns a breaker failure into a problem response.
 * Timeout → 504 {@code GATEWAY_UPSTREAM_TIMEOUT}; breaker open, connection refused or anything else →
 * 503 {@code UPSTREAM_UNAVAILABLE}. Only answers requests forwarded by a route (the gateway route attribute
 * is set); a direct call from outside gets 404.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    private final ProblemWriter problems;

    public FallbackController(ProblemWriter problems) {
        this.problems = problems;
    }

    @RequestMapping("/fallback/{service}")
    public Mono<Void> fallback(@PathVariable String service, ServerWebExchange exchange) {
        if (exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR) == null) {
            // Called directly from outside, not forwarded by a route's circuit breaker.
            return problems.write(exchange, CommonErrorCode.NOT_FOUND, "No such endpoint.");
        }
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        log.warn("Fallback for {}: {}", service, cause == null ? "unknown" : cause.toString());
        if (isTimeout(cause)) {
            return problems.write(exchange, GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT,
                    "The service did not answer in time.");
        }
        return problems.write(exchange, CommonErrorCode.UPSTREAM_UNAVAILABLE,
                "The service is unavailable. Try again later.");
    }

    private static boolean isTimeout(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException
                    || (t instanceof org.springframework.web.server.ResponseStatusException rse
                        && rse.getStatusCode().value() == 504)) {
                return true;
            }
        }
        return false;
    }
}
