package com.lifeos.gateway.ratelimit;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code lifeos.gateway.rate-limit.*}: per-user token buckets in Redis.
 *
 * @param enabled  off for now (single user, no quota decided); the code path is ready
 * @param defaults the bucket of every route without its own entry
 * @param routes   per route ID overrides, e.g. a stricter bucket for {@code coach}
 */
@ConfigurationProperties("lifeos.gateway.rate-limit")
public record RateLimitProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Bucket defaults,
        @DefaultValue Map<String, Bucket> routes) {

    public Bucket bucketFor(String routeId) {
        return routes.getOrDefault(routeId, defaults);
    }

    /**
     * @param replenishRate tokens added per second, i.e. the sustained requests per second per user
     * @param burstCapacity bucket size, i.e. the short burst a user may send at once
     */
    public record Bucket(
            @DefaultValue("10") int replenishRate,
            @DefaultValue("20") int burstCapacity) {
    }
}
