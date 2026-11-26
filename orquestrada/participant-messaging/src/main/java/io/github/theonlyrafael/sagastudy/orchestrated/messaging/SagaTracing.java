package io.github.theonlyrafael.sagastudy.orchestrated.messaging;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Component
public class SagaTracing {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SagaTracing.class);
    private final Tracer tracer;
    private static final TextMapGetter<Map<String, Object>> HEADERS = new TextMapGetter<>() {
        public Iterable<String> keys(Map<String, Object> headers) { return headers.keySet(); }
        public String get(Map<String, Object> headers, String key) {
            Object value = headers.get(key);
            return value == null ? null : value.toString();
        }
    };

    public SagaTracing(OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer("saga.messaging");
    }

    public static String currentParent() {
        var context = Span.current().getSpanContext();
        if (!context.isValid()) return null;
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + context.getTraceFlags().asHex();
    }

    public void consume(Message delivery, SagaEnvelope event, Runnable action) {
        var parent = W3CTraceContextPropagator.getInstance().extract(Context.root(),
                delivery.getMessageProperties().getHeaders(), HEADERS);
        var span = tracer.spanBuilder(event.type() + " process").setParent(parent).setSpanKind(SpanKind.CONSUMER)
                .setAttribute("saga.id", event.sagaId().toString()).setAttribute("order.id", event.orderId().toString())
                .setAttribute("operation.id", event.operationId().toString()).startSpan();
        try (var scope = span.makeCurrent()) {
            action.run();
            LOG.atInfo().addKeyValue("sagaId", event.sagaId())
                    .addKeyValue("orderId", event.orderId()).addKeyValue("operationId", event.operationId())
                    .log("Processed {}", event.type());
        } catch (RuntimeException ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
        }
    }
}
