package com.reactive.platform.observe;

/**
 * A message that carries both business IDs and distributed trace context.
 *
 * Extends Traceable to provide:
 * - Business IDs: requestId, customerId, eventId, sessionId
 * - Trace context: traceparent, tracestate (W3C format)
 *
 * Use with Log.asyncTracedConsume() for automatic attribute extraction
 * and trace context propagation.
 */
public interface TracedMessage extends Traceable {
    /**
     * W3C traceparent header value for distributed tracing.
     * Empty string if not available.
     */
    String traceparent();

    /**
     * W3C tracestate header value for vendor-specific trace data.
     * Empty string if not available.
     */
    String tracestate();
}
