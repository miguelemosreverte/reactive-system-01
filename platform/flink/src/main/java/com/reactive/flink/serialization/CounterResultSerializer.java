package com.reactive.flink.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterResult;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CounterResultSerializer implements SerializationSchema<CounterResult> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterResultSerializer.class);

    private transient ObjectMapper objectMapper;

    @Override
    public void open(InitializationContext context) {
        objectMapper = new ObjectMapper();
    }

    @Override
    public byte[] serialize(CounterResult element) {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        try {
            return objectMapper.writeValueAsBytes(element);
        } catch (Exception e) {
            LOG.error("Failed to serialize result: {}", element, e);
            return new byte[0];
        }
    }
}
