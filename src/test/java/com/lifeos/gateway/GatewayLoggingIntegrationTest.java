package com.lifeos.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.lifeos.base.http.LifeOsHeaders;
import com.lifeos.gateway.support.TestBackend;
import com.lifeos.gateway.support.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Every log line of a request carries its correlation ID: access log, error handler and logs inside the chain. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.health.redis.enabled=false"
})
@Import(GatewayLoggingIntegrationTest.Jwt.class)
class GatewayLoggingIntegrationTest {

    static final TestBackend BACKEND = TestBackend.start();
    static final String TOKEN = TestJwts.token(UUID.randomUUID().toString());

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void serviceUris(DynamicPropertyRegistry registry) {
        registry.add("STRATEGY_URI", BACKEND::uri);
        registry.add("TRIGGER_URI", TestBackend::deadUri);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop();
    }

    @Test
    void accessLogLineCarriesTheCorrelationId(CapturedOutput output) throws Exception {
        String id = "log-" + UUID.randomUUID();
        assertEquals(200, get("/api/strategy/goals?email=secret@example.com", TOKEN, id).statusCode());

        String line = awaitLine(output, "[" + id + "]", "GET /api/strategy/goals 200");
        assertEquals(-1, line.indexOf("secret@example.com"), "query string must not be logged");
    }

    @Test
    void errorResponsesAreLoggedWithTheirFinalStatus(CapturedOutput output) throws Exception {
        String id = "log-" + UUID.randomUUID();
        assertEquals(404, get("/api/unknown", TOKEN, id).statusCode());
        awaitLine(output, "[" + id + "]", "GET /api/unknown 404");

        HttpResponse<String> unauthenticated = get("/api/strategy/goals", null, null);
        assertEquals(401, unauthenticated.statusCode());
        String generated = unauthenticated.headers().firstValue(LifeOsHeaders.CORRELATION_ID).orElseThrow();
        awaitLine(output, "[" + generated + "]", "GET /api/strategy/goals 401");
    }

    @Test
    void logsInsideTheReactiveChainCarryTheCorrelationId(CapturedOutput output) throws Exception {
        String id = "log-" + UUID.randomUUID();
        assertEquals(503, get("/api/triggers/schedules", TOKEN, id).statusCode());

        // Logged by the fallback controller on a Netty thread: the ID reaches it only through context propagation.
        awaitLine(output, "[" + id + "]", "Fallback for triggers");
        awaitLine(output, "[" + id + "]", "GET /api/triggers/schedules 503");
    }

    /** The access log is written right after the response, so give it a moment. */
    private static String awaitLine(CapturedOutput output, String... parts) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            var match = output.getOut().lines()
                    .filter(line -> Arrays.stream(parts).allMatch(line::contains))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
            Thread.sleep(50);
        }
        fail("No log line contains " + Arrays.toString(parts));
        return null;
    }

    private HttpResponse<String> get(String path, String token, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.header(LifeOsHeaders.CORRELATION_ID, correlationId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class Jwt {
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return TestJwts.decoder();
        }
    }
}
