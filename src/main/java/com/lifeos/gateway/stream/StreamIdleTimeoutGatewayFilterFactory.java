package com.lifeos.gateway.stream;

import com.lifeos.gateway.error.GatewayErrorCode;
import com.lifeos.gateway.error.ProblemWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code StreamIdleTimeout=60s}: ends a streamed response when the service sends nothing for that long.
 *
 * <p>The route's {@code response-timeout} only covers the wait for the response headers; once a stream has
 * started, nothing else limits it. With this filter a stalled stream is cut: the connection to the service is
 * closed and, for {@code text/event-stream}, the client gets a last {@code event: error} whose data is the usual
 * problem body ({@code GATEWAY_UPSTREAM_TIMEOUT}) before the stream ends. Keepalives count as data, so a service
 * sending {@code : ping} every 15 s is never cut by a 60 s limit.
 */
@Component
public class StreamIdleTimeoutGatewayFilterFactory
        extends AbstractGatewayFilterFactory<StreamIdleTimeoutGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(StreamIdleTimeoutGatewayFilterFactory.class);

    private final ProblemWriter problems;

    public StreamIdleTimeoutGatewayFilterFactory(ProblemWriter problems) {
        super(Config.class);
        this.problems = problems;
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("idleTimeout");
    }

    @Override
    public GatewayFilter apply(Config config) {
        Duration idleTimeout = config.getIdleTimeout();
        // Must wrap the response before NettyWriteResponseFilter writes the service's body to it.
        return new OrderedGatewayFilter((exchange, chain) -> chain.filter(exchange.mutate()
                        .response(new IdleTimeoutResponse(exchange, idleTimeout))
                        .build()),
                NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1);
    }

    private final class IdleTimeoutResponse extends ServerHttpResponseDecorator {

        private final ServerWebExchange exchange;
        private final Duration idleTimeout;

        IdleTimeoutResponse(ServerWebExchange exchange, Duration idleTimeout) {
            super(exchange.getResponse());
            this.exchange = exchange;
            this.idleTimeout = idleTimeout;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return super.writeWith(guard(Flux.<DataBuffer>from(body), buffer -> buffer));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return super.writeAndFlushWith(guard(Flux.<Publisher<? extends DataBuffer>>from(body), Mono::just));
        }

        private <T> Flux<T> guard(Flux<T> body, Function<DataBuffer, T> wrap) {
            return body.timeout(idleTimeout).onErrorResume(TimeoutException.class, timeout -> {
                log.warn("{} {}: no data for {}, stream closed", exchange.getRequest().getMethod(),
                        exchange.getRequest().getPath(), idleTimeout);
                return isEventStream() ? Mono.just(wrap.apply(errorEvent())) : Mono.error(timeout);
            });
        }

        private boolean isEventStream() {
            MediaType contentType = getHeaders().getContentType();
            return contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType);
        }

        private DataBuffer errorEvent() {
            String data = new String(problems.toJson(exchange, GatewayErrorCode.GATEWAY_UPSTREAM_TIMEOUT,
                    "The service stopped sending data."), StandardCharsets.UTF_8);
            return bufferFactory().wrap(("event: error\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    public static class Config {

        private Duration idleTimeout = Duration.ofSeconds(60);

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }
    }
}
