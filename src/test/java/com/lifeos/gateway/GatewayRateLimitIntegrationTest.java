package com.lifeos.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.gateway.support.TestBackend;
import com.lifeos.gateway.support.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** Rate limiting switched on, against a real Redis (Testcontainers). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lifeos.gateway.rate-limit.enabled=true",
        "lifeos.gateway.rate-limit.routes.coach.replenish-rate=1",
        "lifeos.gateway.rate-limit.routes.coach.burst-capacity=3"
})
@Import(GatewayRateLimitIntegrationTest.Jwt.class)
class GatewayRateLimitIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static final TestBackend BACKEND = TestBackend.start();

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void serviceUris(DynamicPropertyRegistry registry) {
        registry.add("STRATEGY_URI", BACKEND::uri);
        registry.add("COACH_URI", BACKEND::uri);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @Test
    void userOverTheCoachBurstGetsRateLimitedProblem() throws Exception {
        String token = TestJwts.token(UUID.randomUUID().toString());
        List<Integer> statuses = new ArrayList<>();
        HttpResponse<String> last = null;
        for (int i = 0; i < 5; i++) {
            last = get("/api/coach/recommendations", token);
            statuses.add(last.statusCode());
        }

        assertEquals(List.of(200, 200, 200, 429, 429), statuses);
        assertEquals("RATE_LIMITED", json.readTree(last.body()).get("code").asString());
        assertEquals("0", last.headers().firstValue("X-RateLimit-Remaining").orElseThrow());
    }

    @Test
    void bucketsArePerUserAndPerRoute() throws Exception {
        String busy = TestJwts.token(UUID.randomUUID().toString());
        for (int i = 0; i < 4; i++) {
            get("/api/coach/recommendations", busy);
        }

        assertEquals(429, get("/api/coach/recommendations", busy).statusCode());
        assertEquals(200, get("/api/coach/recommendations", TestJwts.token(UUID.randomUUID().toString())).statusCode(),
                "another user has its own bucket");
        assertEquals(200, get("/api/strategy/goals", busy).statusCode(), "strategy has its own, larger bucket");
        assertTrue(get("/api/strategy/goals", busy).headers().firstValue("X-RateLimit-Remaining").isPresent());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class Jwt {
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return TestJwts.decoder();
        }
    }
}
