package com.reactive.flink.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reactive.flink.model.CounterEvent;
import com.reactive.flink.model.CounterResult;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CounterProcessor extends KeyedProcessFunction<String, CounterEvent, CounterResult> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(CounterProcessor.class);

    private final String droolsUrl;
    private transient ValueState<Integer> counterState;
    private transient CloseableHttpClient httpClient;
    private transient ObjectMapper objectMapper;

    public CounterProcessor(String droolsUrl) {
        this.droolsUrl = droolsUrl;
    }

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Integer> descriptor = new ValueStateDescriptor<>(
                "counterValue",
                Types.INT
        );
        counterState = getRuntimeContext().getState(descriptor);
        httpClient = HttpClients.createDefault();
        objectMapper = new ObjectMapper();
    }

    @Override
    public void close() throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @Override
    public void processElement(CounterEvent event, Context ctx, Collector<CounterResult> out) throws Exception {
        // Get current counter value
        Integer currentValue = counterState.value();
        if (currentValue == null) {
            currentValue = 0;
        }

        // Apply the action
        switch (event.getAction()) {
            case "increment":
                currentValue += event.getValue();
                break;
            case "decrement":
                currentValue -= event.getValue();
                break;
            case "set":
                currentValue = event.getValue();
                break;
            default:
                LOG.warn("Unknown action: {}", event.getAction());
                return;
        }

        // Update state
        counterState.update(currentValue);

        // Call Drools for rule evaluation
        String alert = "NONE";
        String message = "No rules matched";

        try {
            DroolsResponse droolsResponse = callDrools(currentValue);
            alert = droolsResponse.alert;
            message = droolsResponse.message;
        } catch (Exception e) {
            LOG.error("Failed to call Drools: {}", e.getMessage());
            // Continue with default values
        }

        // Emit result
        CounterResult result = new CounterResult(
                event.getSessionId(),
                currentValue,
                alert,
                message
        );
        out.collect(result);

        LOG.info("Processed event: {} -> {}", event, result);
    }

    private DroolsResponse callDrools(int value) throws Exception {
        String url = droolsUrl + "/api/evaluate";
        HttpPost post = new HttpPost(url);
        post.setHeader("Content-Type", "application/json");

        String requestBody = objectMapper.writeValueAsString(new DroolsRequest(value));
        post.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            JsonNode json = objectMapper.readTree(responseBody);

            DroolsResponse droolsResponse = new DroolsResponse();
            droolsResponse.alert = json.path("alert").asText("NONE");
            droolsResponse.message = json.path("message").asText("No message");
            return droolsResponse;
        }
    }

    private static class DroolsRequest {
        public int value;

        public DroolsRequest(int value) {
            this.value = value;
        }
    }

    private static class DroolsResponse {
        public String alert;
        public String message;
    }
}
