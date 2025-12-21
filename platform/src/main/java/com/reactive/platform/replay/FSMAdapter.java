package com.reactive.platform.replay;

import java.util.Map;

/**
 * Adapter interface for Finite State Machines.
 *
 * This allows the platform replay infrastructure to work with
 * any domain-specific FSM without coupling to the domain.
 *
 * Implementations wrap domain-specific FSMs (e.g., CounterFSM)
 * and translate between generic Maps and domain types.
 *
 * @param <S> The state type
 */
public interface FSMAdapter<S> {

    /**
     * Get the initial state.
     */
    S initialState();

    /**
     * Apply an event to a state, producing new state.
     *
     * @param state Current state
     * @param event Event payload as a map
     * @return New state after applying the event
     */
    S apply(S state, Map<String, Object> event);

    /**
     * Convert state to a map for serialization.
     */
    Map<String, Object> stateToMap(S state);

    /**
     * Convert a map to state.
     */
    S mapToState(Map<String, Object> map);

    /**
     * Get the aggregate ID field name.
     * Default is "sessionId".
     */
    default String aggregateIdField() {
        return "sessionId";
    }

    /**
     * Get the event ID field name.
     * Default is "requestId".
     */
    default String eventIdField() {
        return "requestId";
    }
}
