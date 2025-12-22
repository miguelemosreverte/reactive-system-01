package com.reactive.platform.observe;

/**
 * Thread-local context for investigation/debug mode.
 *
 * When a request comes in with X-Debug header (or similar),
 * the filter enables investigation mode for that thread.
 * This activates debug logging and tracing spans.
 */
public final class InvestigationContext {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private InvestigationContext() {}

    /**
     * Enable investigation mode for current thread.
     */
    public static void enable() {
        ACTIVE.set(true);
    }

    /**
     * Disable investigation mode for current thread.
     */
    public static void disable() {
        ACTIVE.remove();
    }

    /**
     * Check if investigation mode is active.
     */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    /**
     * Execute work with investigation mode enabled, then restore previous state.
     */
    public static <T> T withInvestigation(java.util.function.Supplier<T> work) {
        boolean wasActive = isActive();
        enable();
        try {
            return work.get();
        } finally {
            if (!wasActive) {
                disable();
            }
        }
    }
}
