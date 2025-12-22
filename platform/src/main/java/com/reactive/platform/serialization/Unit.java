package com.reactive.platform.serialization;

/**
 * Unit type representing "no meaningful value".
 *
 * Use instead of null when bridging void operations to generic interfaces.
 * Similar to Scala's Unit or Kotlin's Unit.
 *
 * Java's Void requires null as its only value, which violates our no-null standard.
 * Unit provides a proper singleton value.
 *
 * Usage:
 *   Supplier<Unit> voidSupplier = () -> { doWork(); return Unit.VALUE; };
 *   Result<Unit> voidResult = Result.success(Unit.VALUE);
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
