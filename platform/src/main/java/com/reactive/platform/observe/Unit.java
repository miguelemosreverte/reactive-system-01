package com.reactive.platform.observe;

/**
 * Unit type representing "no meaningful value".
 *
 * Use instead of null when bridging void operations to generic interfaces.
 * Similar to Scala's Unit or Kotlin's Unit.
 *
 * Usage:
 *   Supplier<Unit> voidSupplier = () -> { doWork(); return Unit.VALUE; };
 */
public final class Unit {

    /** The singleton Unit value. */
    public static final Unit VALUE = new Unit();

    private Unit() {}

    @Override
    public String toString() {
        return "()";
    }
}
