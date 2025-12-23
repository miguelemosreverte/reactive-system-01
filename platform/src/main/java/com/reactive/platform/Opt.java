package com.reactive.platform;

import java.util.Optional;

/**
 * Null-boundary utility for handling values from third-party APIs.
 *
 * Usage:
 *   import static com.reactive.platform.Opt.or;
 *
 *   String s = or(thirdPartyApi.getValue(), "");
 *   Integer n = or(state.value(), 0);
 *   List<T> list = or(response.getItems(), List.of());
 */
public final class Opt {

    private Opt() {}

    /**
     * Returns the value if non-null, otherwise returns the default.
     * Use this at boundaries where third-party code may return null.
     *
     * @param value the potentially null value from third-party API
     * @param defaultValue the default to use (must not be null)
     * @return value if non-null, otherwise defaultValue
     */
    public static <T> T or(T value, T defaultValue) {
        return Optional.ofNullable(value).orElse(defaultValue);
    }
}
