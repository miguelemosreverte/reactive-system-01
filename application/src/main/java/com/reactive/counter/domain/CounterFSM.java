package com.reactive.counter.domain;

import java.util.function.BiFunction;

/**
 * Pure Counter Finite State Machine.
 *
 * No I/O, no side effects. Just pure state transitions:
 *   (State, Action) -> NewState
 *
 * This is where developers write domain logic.
 * Everything here is testable in isolation.
 */
public final class CounterFSM {

    private CounterFSM() {} // Utility class

    /**
     * Counter action ADT.
     */
    public sealed interface Action {
        record Increment(int value) implements Action {}
        record Decrement(int value) implements Action {}
        record Set(int value) implements Action {}
        record Reset() implements Action {}

        static Action fromString(String action, int value) {
            if (action == null) return new Increment(value);
            return switch (action.toLowerCase()) {
                case "increment" -> new Increment(value);
                case "decrement" -> new Decrement(value);
                case "set" -> new Set(value);
                case "reset" -> new Reset();
                default -> new Increment(value);
            };
        }
    }

    /**
     * The core state transition function.
     * Pure function: (State, Action) -> NewState
     */
    public static CounterState transition(CounterState state, Action action) {
        int newValue = applyAction(state.value(), action);

        return new CounterState(
                newValue,
                CounterState.AlertLevel.PENDING,
                actionToMessage(action, state.value(), newValue)
        );
    }

    /**
     * Apply an action to compute new value.
     */
    public static int applyAction(int currentValue, Action action) {
        return switch (action) {
            case Action.Increment inc -> currentValue + inc.value();
            case Action.Decrement dec -> currentValue - dec.value();
            case Action.Set set -> set.value();
            case Action.Reset ignored -> 0;
        };
    }

    /**
     * Generate message for action.
     */
    public static String actionToMessage(Action action, int oldValue, int newValue) {
        return switch (action) {
            case Action.Increment inc ->
                    String.format("Incremented by %d: %d -> %d", inc.value(), oldValue, newValue);
            case Action.Decrement dec ->
                    String.format("Decremented by %d: %d -> %d", dec.value(), oldValue, newValue);
            case Action.Set set ->
                    String.format("Set to %d (was %d)", set.value(), oldValue);
            case Action.Reset ignored ->
                    String.format("Reset to 0 (was %d)", oldValue);
        };
    }

    /**
     * Suggest alert level based on value.
     * This is the pure rule - actual evaluation happens in Drools.
     */
    public static CounterState.AlertLevel suggestAlert(int value) {
        if (value >= 100) return CounterState.AlertLevel.CRITICAL;
        if (value >= 75) return CounterState.AlertLevel.HIGH_RISK;
        if (value >= 50) return CounterState.AlertLevel.MEDIUM_RISK;
        if (value >= 25) return CounterState.AlertLevel.LOW_RISK;
        if (value > 0) return CounterState.AlertLevel.NORMAL;
        return CounterState.AlertLevel.NONE;
    }

    /**
     * Apply alert to state.
     */
    public static CounterState applyAlert(CounterState state, CounterState.AlertLevel alert, String message) {
        return new CounterState(state.value(), alert, message);
    }

    /**
     * Create a stateful processor (for use in Flink).
     * Returns a function that maintains state across invocations.
     */
    public static BiFunction<CounterState, Action, CounterState> processor() {
        return CounterFSM::transition;
    }
}
