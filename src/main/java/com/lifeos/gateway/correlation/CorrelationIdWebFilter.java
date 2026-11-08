package com.lifeos.gateway.correlation;

import com.lifeos.base.correlation.CorrelationIds;
import com.lifeos.base.http.LifeOsHeaders;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * First filter of every request, before Spring Security: keeps a valid client {@code X-Correlation-Id} or
 * replaces it, forwards the final value to the service, echoes it in the response and stores it as an exchange
 * attribute so error responses (401, 404, 503...) can include it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationIds.orNew(
                exchange.getRequest().getHeaders().getFirst(LifeOsHeaders.CORRELATION_ID));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(LifeOsHeaders.CORRELATION_ID, correlationId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, correlationId);
        mutated.getResponse().getHeaders().set(LifeOsHeaders.CORRELATION_ID, correlationId);
        return chain.filter(mutated)
                .contextWrite(context -> context.put(CorrelationIds.MDC_KEY, correlationId));
    }

    /** The correlation ID of this exchange, or {@code null} if the filter has not run. */
    public static String of(ServerWebExchange exchange) {
        return exchange.getAttribute(ATTRIBUTE);
    }
}
