package com.reactive.counter.replay;

import com.reactive.counter.domain.CounterFSM;
import com.reactive.counter.domain.CounterState;
import com.reactive.platform.replay.FSMAdapter;

import java.util.Map;

/**
 * Adapter connecting the Counter FSM to the platform replay infrastructure.
 *
 * Translates between generic Map payloads and Counter domain types.
 */
public class CounterFSMAdapter implements FSMAdapter<CounterState> {

    @Override
    public CounterState initialState() {
        return CounterState.initial();
    }

    @Override
    public CounterState apply(CounterState state, Map<String, Object> event) {
        // Extract action and value from event payload
        String actionStr = getOrDefault(event, "action", "increment");
        int value = getIntOrDefault(event, "value", 1);

        // Convert to domain Action
        CounterFSM.Action action = CounterFSM.Action.fromString(actionStr, value);

        // Apply through the pure FSM
        return CounterFSM.transition(state, action);
    }

    @Override
    public Map<String, Object> stateToMap(CounterState state) {
        return Map.of(
                "value", state.value(),
                "alert", state.alert().name(),
                "message", state.message()
        );
    }

    @Override
    public CounterState mapToState(Map<String, Object> map) {
        int value = getIntOrDefault(map, "value", 0);
        String alertStr = getOrDefault(map, "alert", "NONE");
        String message = getOrDefault(map, "message", "");

        return new CounterState(
                value,
                CounterState.AlertLevel.fromString(alertStr),
                message
        );
    }

    @Override
    public String aggregateIdField() {
        return "sessionId";
    }

    @Override
    public String eventIdField() {
        return "requestId";
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static <T> T getOrDefault(Map<String, Object> map, String key, T defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    private static int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
