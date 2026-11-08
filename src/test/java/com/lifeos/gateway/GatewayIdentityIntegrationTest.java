package com.lifeos.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.base.correlation.CorrelationIds;
import com.lifeos.gateway.support.TestBackend;
import com.lifeos.gateway.support.TestJwts;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real gateway on a random port in front of an echo backend that returns the headers it received, called with
 * real RS256-signed JWTs: proves what the services actually get and what clients see on errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.health.redis.enabled=false"
})
@Import(GatewayIdentityIntegrationTest.TestRoutes.class)
class GatewayIdentityIntegrationTest {

    static final TestBackend ECHO = TestBackend.start();
    static final UUID USER = UUID.fromString("0197a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @AfterAll
    static void stopEcho() {
        ECHO.stop();
    }

    @Test
    void routedRequestCarriesUserIdFromJwtAndTheOriginalToken() throws Exception {
        String token = TestJwts.token(USER.toString());
        HttpResponse<String> response = get("/api/echo/x", token, Map.of("X-User-Id", UUID.randomUUID().toString()));

        assertEquals(200, response.statusCode());
        JsonNode seen = json.readTree(response.body());
        assertEquals(USER.toString(), seen.get("x-user-id").asString(), "client value must be replaced");
        assertEquals("Bearer " + token, seen.get("authorization").asString(), "token forwarded unchanged");
    }

    @Test
    void correlationIdIsForwardedAndEchoed() throws Exception {
        HttpResponse<String> response = get("/api/echo/x", TestJwts.token(USER.toString()),
                Map.of("X-Correlation-Id", "mobile-1"));

        assertEquals("mobile-1", json.readTree(response.body()).get("x-correlation-id").asString());
        assertEquals("mobile-1", response.headers().firstValue("X-Correlation-Id").orElseThrow());
    }

    @Test
    void unsafeCorrelationIdIsReplacedBeforeReachingTheService() throws Exception {
        HttpResponse<String> response = get("/api/echo/x", TestJwts.token(USER.toString()),
                Map.of("X-Correlation-Id", "bad id<script>"));

        String forwarded = json.readTree(response.body()).get("x-correlation-id").asString();
        assertNotEquals("bad id<script>", forwarded);
        assertTrue(CorrelationIds.isValid(forwarded));
    }

    @Test
    void missingTokenIsUnauthenticatedProblem() throws Exception {
        HttpResponse<String> response = get("/api/echo/x", null, Map.of());

        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));
        JsonNode body = json.readTree(response.body());
        assertEquals("UNAUTHENTICATED", body.get("code").asString());
        assertEquals(response.headers().firstValue("X-Correlation-Id").orElseThrow(),
                body.get("correlationId").asString());
    }

    @Test
    void tokenFromAnotherIssuerKeyIsRejected() throws Exception {
        HttpResponse<String> response = get("/api/echo/x", TestJwts.token(TestJwts.UNTRUSTED.getPrivate(), USER.toString()), Map.of());
        assertEquals(401, response.statusCode());
        assertEquals("UNAUTHENTICATED", json.readTree(response.body()).get("code").asString());
    }

    @Test
    void nonUuidSubjectIsRejectedBeforeRouting() throws Exception {
        HttpResponse<String> response = get("/api/echo/x", TestJwts.token("service-account"), Map.of());
        assertEquals(401, response.statusCode());
        assertEquals("UNAUTHENTICATED", json.readTree(response.body()).get("code").asString());
    }

    @Test
    void unknownPathIsNotFoundProblem() throws Exception {
        HttpResponse<String> response = get("/api/nowhere", TestJwts.token(USER.toString()), Map.of());
        assertEquals(404, response.statusCode());
        assertEquals("NOT_FOUND", json.readTree(response.body()).get("code").asString());
    }

    @Test
    void healthIsPublic() throws Exception {
        assertEquals(200, get("/actuator/health", null, Map.of()).statusCode());
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private HttpResponse<String> get(String path, String token, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class TestRoutes {

        /** Stands in for Keycloak's JWKS: trusts only TRUSTED. */
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return TestJwts.decoder();
        }

        @Bean
        RouteLocator echoRoute(RouteLocatorBuilder routes) {
            return routes.routes()
                    .route("echo", r -> r.path("/api/echo/**")
                            .uri(ECHO.uri()))
                    .build();
        }
    }
}
