package com.lifeos.gateway.security;

import com.lifeos.base.error.CommonErrorCode;
import com.lifeos.gateway.error.ProblemWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * The outer security layer. Every request needs a valid Keycloak JWT (signature, expiry, issuer and the
 * {@code lifeos-api} audience, configured with {@code spring.security.oauth2.resourceserver.jwt.*}),
 * except {@code lifeos.security.public-paths}. Stateless: no session, no CSRF (no cookies).
 * The services validate the same JWT again (inner layer).
 */
@Configuration
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemWriter problems,
                                                  GatewaySecurityProperties properties) {
        ServerAuthenticationEntryPoint entryPoint = unauthenticated(problems);
        ServerAccessDeniedHandler accessDenied = forbidden(problems);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(properties.publicPaths().toArray(String[]::new)).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .build();
    }

    /**
     * 401 with an RFC 6750 {@code WWW-Authenticate} challenge and a problem body. Spring's bearer entry point
     * completes the response itself, so the challenge is written here. Only the error code is included
     * ({@code invalid_token}); the description ("Jwt expired", signature details) is not sent to the client.
     */
    private static ServerAuthenticationEntryPoint unauthenticated(ProblemWriter problems) {
        return (exchange, ex) -> {
            String challenge = ex instanceof OAuth2AuthenticationException oauth2
                    ? "Bearer error=\"" + oauth2.getError().getErrorCode() + "\""
                    : "Bearer";
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
            return problems.write(exchange, CommonErrorCode.UNAUTHENTICATED, "A valid access token is required.");
        };
    }

    private static ServerAccessDeniedHandler forbidden(ProblemWriter problems) {
        return (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
            return problems.write(exchange, CommonErrorCode.FORBIDDEN, "You are not allowed to perform this action.");
        };
    }
}
