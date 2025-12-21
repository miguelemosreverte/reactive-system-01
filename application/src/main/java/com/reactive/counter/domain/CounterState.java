package com.reactive.counter.domain;

/**
 * Immutable counter state.
 *
 * Pure data class - no behavior, just holds state.
 * Similar to Scala case class.
 */
public record CounterState(
        int value,
        AlertLevel alert,
        String message
) {
    /**
     * Alert levels.
     */
    public enum AlertLevel {
        NONE,
        PENDING,
        NORMAL,
        LOW_RISK,
        MEDIUM_RISK,
        HIGH_RISK,
        CRITICAL;

        public static AlertLevel fromString(String s) {
            if (s == null) return NONE;
            return switch (s.toUpperCase()) {
                case "NORMAL" -> NORMAL;
                case "LOW_RISK" -> LOW_RISK;
                case "MEDIUM_RISK" -> MEDIUM_RISK;
                case "HIGH_RISK" -> HIGH_RISK;
                case "CRITICAL" -> CRITICAL;
                case "PENDING" -> PENDING;
                default -> NONE;
            };
        }
    }

    /**
     * Initial state.
     */
    public static CounterState initial() {
        return new CounterState(0, AlertLevel.NONE, "No events processed yet");
    }

    /**
     * Create new state with updated value.
     */
    public CounterState withValue(int newValue) {
        return new CounterState(newValue, alert, message);
    }

    /**
     * Create new state with updated alert.
     */
    public CounterState withAlert(AlertLevel newAlert, String newMessage) {
        return new CounterState(value, newAlert, newMessage);
    }

    /**
     * Copy with updates (like Scala copy method).
     */
    public CounterState copy(Integer newValue, AlertLevel newAlert, String newMessage) {
        return new CounterState(
                newValue != null ? newValue : value,
                newAlert != null ? newAlert : alert,
                newMessage != null ? newMessage : message
        );
    }
}
