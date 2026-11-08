package com.lifeos.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.gateway.support.TestBackend;
import com.lifeos.gateway.support.TestJwts;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The production routes from application.yml, pointed at fake services: path routing, the 1 s response timeout,
 * the timeout-free coach stream route, an unreachable service and the circuit breaker opening.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.health.redis.enabled=false",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=1s",
        "lifeos.gateway.circuit-breaker.sliding-window-size=4",
        "lifeos.gateway.circuit-breaker.minimum-number-of-calls=4",
        "lifeos.gateway.circuit-breaker.wait-in-open-state=60s",
        "lifeos.gateway.circuit-breaker.time-limit=3s"
})
@Import(GatewayRoutingIntegrationTest.Jwt.class)
class GatewayRoutingIntegrationTest {

    static final TestBackend BACKEND = TestBackend.start();
    static final String TOKEN = TestJwts.token(UUID.randomUUID().toString());

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    ReactiveResilience4JCircuitBreakerFactory breakers;

    @DynamicPropertySource
    static void serviceUris(DynamicPropertyRegistry registry) {
        registry.add("STRATEGY_URI", BACKEND::uri);
        registry.add("COACH_URI", BACKEND::uri);
        registry.add("NOTIFICATION_URI", BACKEND::uri);
        registry.add("TRIGGER_URI", TestBackend::deadUri);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @Test
    void eachPrefixGoesToItsServiceWithThePathUnchanged() throws Exception {
        for (String path : new String[] {"/api/strategy/goals/1", "/api/coach/recommendations", "/api/notifications/devices"}) {
            HttpResponse<String> response = send("GET", path);
            assertEquals(200, response.statusCode(), path);
            assertEquals(path, json.readTree(response.body()).get("path").asString());
        }
    }

    @Test
    void slowServiceIsGatewayTimeout() throws Exception {
        HttpResponse<String> response = send("GET", "/api/strategy/slow");

        assertEquals(504, response.statusCode());
        assertEquals("GATEWAY_UPSTREAM_TIMEOUT", code(response));
    }

    @Test
    void coachStreamIsNotCutByTheResponseTimeout() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = send("POST", "/api/coach/conversations/c1/messages");
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("data: part-4"), response.body());
        assertTrue(tookMs > 1500, "stream ran past the 1 s response timeout: " + tookMs + " ms");
    }

    @Test
    void unreachableServiceIsUnavailableAndOpensItsBreakerOnly() throws Exception {
        for (int i = 0; i < 4; i++) {
            HttpResponse<String> response = send("GET", "/api/triggers/schedules");
            assertEquals(503, response.statusCode());
            assertEquals("UPSTREAM_UNAVAILABLE", code(response));
        }

        assertEquals(CircuitBreaker.State.OPEN, state("triggers"));
        HttpResponse<String> whileOpen = send("GET", "/api/triggers/schedules");
        assertEquals(503, whileOpen.statusCode());
        assertEquals("UPSTREAM_UNAVAILABLE", code(whileOpen));

        assertEquals(200, send("GET", "/api/strategy/goals").statusCode(), "other routes keep working");
    }

    @Test
    void fallbackIsNotReachableFromOutside() throws Exception {
        assertEquals(404, send("GET", "/fallback/strategy").statusCode());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TOKEN)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String code(HttpResponse<String> response) {
        JsonNode body = json.readTree(response.body());
        return body.get("code").asString();
    }

    private CircuitBreaker.State state(String id) {
        return breakers.getCircuitBreakerRegistry().circuitBreaker(id).getState();
    }

    @TestConfiguration
    static class Jwt {
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return TestJwts.decoder();
        }
    }
}
