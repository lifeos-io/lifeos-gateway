package com.lifeos.gateway.correlation;

import com.lifeos.base.correlation.CorrelationIds;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/**
 * Bridges the Reactor context and the logging MDC: whatever runs inside a request's reactive chain gets the
 * correlation ID that {@link CorrelationIdWebFilter} wrote into the context, on whichever thread it runs.
 *
 * <p>Registered through {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor}; Reactor applies it
 * because {@code spring.reactor.context-propagation=auto}.
 */
public class CorrelationIdMdcAccessor implements ThreadLocalAccessor<String> {

    @Override
    public Object key() {
        return CorrelationIds.MDC_KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(CorrelationIds.MDC_KEY);
    }

    @Override
    public void setValue(String value) {
        MDC.put(CorrelationIds.MDC_KEY, value);
    }

    @Override
    public void setValue() {
        MDC.remove(CorrelationIds.MDC_KEY);
    }
}
