package com.lifeos.gateway.ratelimit;

import com.lifeos.base.error.CommonErrorCode;
import com.lifeos.base.http.LifeOsHeaders;
import com.lifeos.gateway.error.ProblemWriter;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-user rate limit for every routed request, keyed by the user ID the {@code UserIdHeaderFilter} set from
 * the JWT (one bucket per user and route). Over the limit → 429 {@code RATE_LIMITED}; the
 * {@code X-RateLimit-*} headers show the remaining budget.
 *
 * <p>Off unless {@code lifeos.gateway.rate-limit.enabled=true}. If Redis is down the limiter lets requests
 * through (fail-open): an outage of the counter must not take the whole API down.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class UserRateLimitFilter implements GlobalFilter, Ordered {

    private final RedisRateLimiter limiter;
    private final RateLimitProperties properties;
    private final ProblemWriter problems;

    public UserRateLimitFilter(RedisRateLimiter limiter, RateLimitProperties properties, ProblemWriter problems) {
        this.limiter = limiter;
        this.properties = properties;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String userId = exchange.getRequest().getHeaders().getFirst(LifeOsHeaders.USER_ID);
        if (!properties.enabled() || route == null || userId == null) {
            return chain.filter(exchange);
        }
        ensureConfig(route.getId());
        return limiter.isAllowed(route.getId(), userId).flatMap(response -> {
            response.getHeaders().forEach((name, value) -> exchange.getResponse().getHeaders().set(name, value));
            if (response.isAllowed()) {
                return chain.filter(exchange);
            }
            return problems.write(exchange, CommonErrorCode.RATE_LIMITED, "Too many requests. Slow down.");
        });
    }

    /** RedisRateLimiter keeps one config per route ID; register ours the first time a route is seen. */
    private void ensureConfig(String routeId) {
        Map<String, RedisRateLimiter.Config> configs = limiter.getConfig();
        if (configs.containsKey(routeId)) {
            return;
        }
        synchronized (configs) {
            if (!configs.containsKey(routeId)) {
                RateLimitProperties.Bucket bucket = properties.bucketFor(routeId);
                configs.put(routeId, new RedisRateLimiter.Config()
                        .setReplenishRate(bucket.replenishRate())
                        .setBurstCapacity(bucket.burstCapacity())
                        .setRequestedTokens(1));
            }
        }
    }

    /** Right after the identity filter, which provides the user ID. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
