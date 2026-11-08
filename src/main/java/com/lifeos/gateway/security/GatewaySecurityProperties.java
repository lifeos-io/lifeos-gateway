package com.lifeos.gateway.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code lifeos.security.*}, the same property name the services use (lifeos-base-web).
 *
 * @param publicPaths path patterns reachable without a token; everything else needs a valid JWT
 */
@ConfigurationProperties("lifeos.security")
public record GatewaySecurityProperties(
        @DefaultValue({"/actuator/health", "/actuator/health/**", "/actuator/info"})
        List<String> publicPaths) {
}
