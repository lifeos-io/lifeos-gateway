package com.lifeos.gateway.error;

import com.lifeos.base.correlation.CorrelationIds;
import com.lifeos.base.error.CommonErrorCode;
import com.lifeos.base.error.ErrorCode;
import com.lifeos.gateway.correlation.CorrelationIdWebFilter;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Every error that reaches the gateway's top level becomes a problem response: no matching route (404),
 * service unreachable (503), service too slow (504), anything else (500, details only in the log).
 * Runs before Spring Boot's default handler (order -1).
 */
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private final ProblemWriter problems;

    public GatewayErrorWebExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        Mapped mapped = map(ex);
        if (mapped.code().status() >= 500) {
            // Error handlers run outside the filter chain's Reactor context, so set the MDC by hand.
            String correlationId = CorrelationIdWebFilter.of(exchange);
            try (MDC.MDCCloseable ignored = correlationId == null ? null
                    : MDC.putCloseable(CorrelationIds.MDC_KEY, correlationId)) {
                log.warn("{} {} failed: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                        mapped.code().code(), ex);
            }
        }
        return problems.write(exchange, mapped.code(), mapped.detail());
    }

    static Mapped map(Throwable ex) {
        if (hasCause(ex, TimeoutException.class)) {
            return new Mapped(GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT, "The service did not answer in time.");
        }
        if (hasCause(ex, ConnectException.class)) {
            return new Mapped(CommonErrorCode.UPSTREAM_UNAVAILABLE, "The service is unavailable. Try again later.");
        }
        if (ex instanceof ResponseStatusException rse) {
            return switch (rse.getStatusCode().value()) {
                case 404 -> new Mapped(CommonErrorCode.NOT_FOUND, "No such endpoint.");
                case 405, 406, 415, 400 -> new Mapped(CommonErrorCode.MALFORMED_REQUEST, "The request is not valid.");
                case 503 -> new Mapped(CommonErrorCode.UPSTREAM_UNAVAILABLE, "The service is unavailable. Try again later.");
                case 504 -> new Mapped(GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT, "The service did not answer in time.");
                case 429 -> new Mapped(CommonErrorCode.RATE_LIMITED, "Too many requests. Slow down.");
                default -> rse.getStatusCode().is4xxClientError()
                        ? new Mapped(CommonErrorCode.MALFORMED_REQUEST, "The request is not valid.")
                        : new Mapped(CommonErrorCode.INTERNAL_ERROR, "Something went wrong on our side.");
            };
        }
        return new Mapped(CommonErrorCode.INTERNAL_ERROR, "Something went wrong on our side.");
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    record Mapped(ErrorCode code, String detail) {
    }
}
