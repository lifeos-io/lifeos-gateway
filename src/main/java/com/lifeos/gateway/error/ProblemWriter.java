package com.lifeos.gateway.error;

import com.lifeos.base.error.ErrorCode;
import com.lifeos.gateway.correlation.CorrelationIdWebFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the gateway's own error responses in the same RFC 9457 shape the services use
 * ({@code lifeos-base-web}): {@code type, title, status, detail, instance, code, correlationId}.
 * Clients therefore see one error format whether the gateway or a service answered.
 */
@Component
public class ProblemWriter {

    public static final String TYPE_PREFIX = "urn:lifeos:error:";

    private final JsonMapper jsonMapper;

    public ProblemWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(HttpStatusCode.valueOf(code.status()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(toJson(exchange, code, detail));
        return response.writeWith(Mono.just(buffer));
    }

    /** The problem body alone, e.g. as the data of an SSE {@code error} event once a stream is under way. */
    public byte[] toJson(ServerWebExchange exchange, ErrorCode code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_PREFIX + code.code());
        body.put("title", code.title());
        body.put("status", code.status());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getPath().value());
        body.put("code", code.code());
        String correlationId = CorrelationIdWebFilter.of(exchange);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        return jsonMapper.writeValueAsBytes(body);
    }
}
