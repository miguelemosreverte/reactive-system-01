package com.reactive.flink.serialization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterEvent;
import com.reactive.platform.observe.Log;
import com.reactive.platform.observe.Log.SpanHandle;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Deserializer for CounterEvent messages from Kafka.
 *
 * BOUNDARY: This is where third-party data enters our system.
 * Record compact constructor normalizes all fields.
 * With-prefixed methods used for runtime fields.
 */
public class CounterEventDeserializer implements DeserializationSchema<CounterEvent> {
    private static final long serialVersionUID = 5L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterEventDeserializer.class);

    private transient ObjectMapper objectMapper;

    @Override
    public void open(InitializationContext context) {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public CounterEvent deserialize(byte[] message) throws IOException {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        long now = System.currentTimeMillis();

        // BOUNDARY: Jackson deserialization - compact constructor normalizes fields
        CounterEvent event = objectMapper.readValue(message, CounterEvent.class)
                .withDeserializedAt(now);

        // Auto-extracts business IDs and manages MDC
        SpanHandle span = Log.tracedReceive("flink.deserialize", event, Log.SpanType.CONSUMER);

        // Only operation-specific attrs needed
        span.attr("message.size", message.length);
        span.attr("counter.action", event.action());

        if (event.timestamp() > 0) {
            span.attr("kafka.transit_time_ms", now - event.timestamp());
        }

        span.success();
        return event;
    }

    @Override
    public boolean isEndOfStream(CounterEvent nextElement) {
        return false;
    }

    @Override
    public TypeInformation<CounterEvent> getProducedType() {
        return TypeInformation.of(CounterEvent.class);
    }
}
