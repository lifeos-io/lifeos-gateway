package com.lifeos.gateway.security;

import com.lifeos.base.error.CommonErrorCode;
import com.lifeos.base.http.LifeOsHeaders;
import com.lifeos.gateway.error.ProblemWriter;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Identity header contract for every routed request: whatever {@code X-User-Id} the client sent is removed and
 * the header is set from the JWT {@code sub}. The {@code Authorization} header is forwarded unchanged so the
 * service can validate the token itself. A token whose {@code sub} is not a UUID is rejected with 401, since
 * every service uses {@code sub} as its {@code user_id}.
 */
@Component
public class UserIdHeaderFilter implements GlobalFilter, Ordered {

    private final ProblemWriter problems;

    public UserIdHeaderFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(token -> Optional.ofNullable(uuidOrNull(token.getToken().getSubject())))
                .defaultIfEmpty(Optional.empty())
                .flatMap(userId -> {
                    if (userId.isEmpty()) {
                        return problems.write(exchange, CommonErrorCode.UNAUTHENTICATED,
                                "The access token has no valid user ID.");
                    }
                    ServerWebExchange mutated = exchange.mutate()
                            .request(request -> request.headers(headers ->
                                    headers.set(LifeOsHeaders.USER_ID, userId.get().toString())))
                            .build();
                    return chain.filter(mutated);
                });
    }

    /** Before routing and before any other global filter, so no filter ever sees the client's value. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    static UUID uuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
