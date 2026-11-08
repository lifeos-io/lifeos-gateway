package com.lifeos.gateway.correlation;

import com.lifeos.base.correlation.CorrelationIds;
import com.lifeos.base.http.LifeOsHeaders;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.SignalType;

/**
 * One log line per request once it is fully done: {@code GET /api/strategy/goals 200 12ms}, with the
 * correlation ID in the MDC. Wraps the whole handler, so responses written by the error handler (404, 503...)
 * show their final status too, and a streamed answer logs its full duration when the stream ends.
 *
 * <p>The query string is left out (it may carry personal data). Actuator calls (health probes) are not logged.
 * Logger {@code com.lifeos.gateway.access} can be turned off on its own.
 */
@Component
public class AccessLogDecorator implements HttpHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger("com.lifeos.gateway.access");

    @Override
    public HttpHandler apply(HttpHandler handler) {
        return (request, response) -> {
            if (request.getPath().value().startsWith("/actuator")) {
                return handler.handle(request, response);
            }
            long start = System.nanoTime();
            return handler.handle(request, response)
                    .doFinally(signal -> log(request, response, signal, start));
        };
    }

    private static void log(ServerHttpRequest request, ServerHttpResponse response, SignalType signal, long start) {
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        // Set by CorrelationIdWebFilter; this decorator runs outside the reactive chain that carries it.
        String correlationId = response.getHeaders().getFirst(LifeOsHeaders.CORRELATION_ID);
        HttpStatusCode status = response.getStatusCode();
        String outcome = signal == SignalType.CANCEL ? "cancelled" : String.valueOf(status == null ? 200 : status.value());
        try (MDC.MDCCloseable ignored = correlationId == null ? null : MDC.putCloseable(CorrelationIds.MDC_KEY, correlationId)) {
            log.info("{} {} {} {}ms", request.getMethod(), request.getPath().value(), outcome, millis);
        }
    }
}
