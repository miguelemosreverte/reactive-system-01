package com.reactive.platform.base;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Functional Result type - like Scala's Try monad.
 *
 * Represents either a success with a value, or a failure with an error.
 * Immutable and null-safe. Provides monadic operations for composing
 * fallible computations.
 *
 * <pre>{@code
 * Result.of(() -> riskyOperation())
 *       .map(x -> transform(x))
 *       .filter(x -> x > 0)
 *       .recover(ex -> fallbackValue)
 *       .getOrElse(defaultValue);
 * }</pre>
 *
 * @param <A> The success value type
 */
public sealed interface Result<A> permits Result.Success, Result.Failure {

    // ========================================================================
    // Pattern matching
    // ========================================================================

    boolean isSuccess();

    default boolean isFailure() {
        return !isSuccess();
    }

    /**
     * Get value if success, throw if failure.
     */
    A getOrThrow();

    /**
     * Get value if success, return default if failure.
     */
    A getOrElse(A defaultValue);

    /**
     * Get value if success, compute default if failure.
     */
    A getOrElse(Supplier<A> defaultSupplier);

    /**
     * Get error if failure, empty if success.
     */
    Optional<Throwable> error();

    /**
     * Get value if success, empty if failure.
     */
    Optional<A> toOptional();

    // ========================================================================
    // Functional combinators
    // ========================================================================

    /**
     * Map the success value.
     */
    <B> Result<B> map(Function<A, B> f);

    /**
     * FlatMap - chain operations that return Result.
     */
    <B> Result<B> flatMap(Function<A, Result<B>> f);

    /**
     * Handle both cases.
     */
    <B> B fold(Function<Throwable, B> onFailure, Function<A, B> onSuccess);

    /**
     * Execute side effect on success.
     */
    Result<A> onSuccess(Consumer<A> action);

    /**
     * Execute side effect on failure.
     */
    Result<A> onFailure(Consumer<Throwable> action);

    /**
     * Recover from failure.
     */
    Result<A> recover(Function<Throwable, A> f);

    /**
     * Recover from failure with another Result.
     */
    Result<A> recoverWith(Function<Throwable, Result<A>> f);

    /**
     * Try alternative Result if this one failed.
     */
    Result<A> orElse(Result<A> alternative);

    /**
     * Try alternative Result (lazy) if this one failed.
     */
    Result<A> orElse(Supplier<Result<A>> alternative);

    /**
     * Filter success value by predicate. Fails with NoSuchElementException if predicate doesn't match.
     */
    Result<A> filter(Predicate<A> predicate);

    /**
     * Filter with custom error supplier.
     */
    Result<A> filterOrElse(Predicate<A> predicate, Function<A, Throwable> errorSupplier);

    /**
     * Transform the error (if failure).
     */
    Result<A> mapFailure(Function<Throwable, Throwable> f);

    /**
     * Returns a Result containing the exception if this is a Failure,
     * or a Failure with UnsupportedOperationException if this is Success.
     * Like Scala's Try.failed
     */
    Result<Throwable> failed();

    /**
     * Combine with another Result. Returns tuple-like record.
     */
    <B> Result<Pair<A, B>> zip(Result<B> other);

    /**
     * Execute a side effect that may fail, preserving the original value on success.
     */
    Result<A> andThen(ThrowingRunnable action);

    /**
     * Peek at success value (alias for onSuccess).
     */
    default Result<A> peek(Consumer<A> action) {
        return onSuccess(action);
    }

    // ========================================================================
    // Factory methods
    // ========================================================================

    static <A> Result<A> success(A value) {
        return new Success<>(value);
    }

    static <A> Result<A> failure(Throwable error) {
        return new Failure<>(error);
    }

    static <A> Result<A> failure(String message) {
        return new Failure<>(new RuntimeException(message));
    }

    /**
     * Wrap a throwing supplier.
     */
    static <A> Result<A> of(ThrowingSupplier<A> supplier) {
        try {
            return success(supplier.get());
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * Run a throwing action (void), returning success or failure.
     * Useful for cleanup operations where we want to ignore exceptions.
     */
    static Result<Boolean> run(ThrowingRunnable action) {
        try {
            action.run();
            return success(true);
        } catch (Throwable t) {
            return failure(t);
        }
    }

    /**
     * Sleep for specified milliseconds, handling InterruptedException functionally.
     * Restores interrupt flag on interruption.
     *
     * @param millis duration to sleep in milliseconds
     * @return success(true) if sleep completed, failure if interrupted
     */
    static Result<Boolean> sleep(long millis) {
        try {
            Thread.sleep(millis);
            return success(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(e);
        }
    }

    /**
     * Sleep for specified duration, handling InterruptedException functionally.
     * Restores interrupt flag on interruption.
     *
     * @param duration the duration to sleep
     * @return success(true) if sleep completed, failure if interrupted
     */
    static Result<Boolean> sleep(java.time.Duration duration) {
        return sleep(duration.toMillis());
    }

    /**
     * Sleep for specified time with nanosecond precision.
     * Restores interrupt flag on interruption.
     *
     * @param millis milliseconds to sleep
     * @param nanos additional nanoseconds (0-999999)
     * @return success(true) if sleep completed, failure if interrupted
     */
    static Result<Boolean> sleep(long millis, int nanos) {
        try {
            Thread.sleep(millis, nanos);
            return success(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(e);
        }
    }

    /**
     * Wait for thread to complete with timeout.
     * Restores interrupt flag on interruption.
     *
     * @param thread the thread to join
     * @param millis timeout in milliseconds
     * @return success(true) if join completed, failure if interrupted
     */
    static Result<Boolean> join(Thread thread, long millis) {
        try {
            thread.join(millis);
            return success(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(e);
        }
    }

    /**
     * Wait for thread to complete without timeout.
     * Restores interrupt flag on interruption.
     *
     * @param thread the thread to join
     * @return success(true) if join completed, failure if interrupted
     */
    static Result<Boolean> join(Thread thread) {
        try {
            thread.join();
            return success(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(e);
        }
    }

    /**
     * Flatten nested Result.
     */
    static <A> Result<A> flatten(Result<Result<A>> nested) {
        return nested.flatMap(Function.identity());
    }

    /**
     * Sequence a list of Results into a Result of list.
     * Fails fast on first failure.
     */
    static <A> Result<List<A>> sequence(List<Result<A>> results) {
        List<A> values = new ArrayList<>();
        for (Result<A> result : results) {
            if (result.isFailure()) {
                return result.map(v -> null); // Cast to Result<List<A>>
            }
            values.add(result.getOrThrow());
        }
        return success(values);
    }

    /**
     * Map each element with a throwing function, then sequence.
     */
    static <A, B> Result<List<B>> traverse(List<A> items, ThrowingFunction<A, B> f) {
        List<Result<B>> results = new ArrayList<>();
        for (A item : items) {
            results.add(of(() -> f.apply(item)));
        }
        return sequence(results);
    }

    // ========================================================================
    // Helper types
    // ========================================================================

    /**
     * Simple pair type for zip operations.
     */
    record Pair<A, B>(A first, B second) {}

    // ========================================================================
    // Implementations
    // ========================================================================

    record Success<A>(A value) implements Result<A> {
        public Success {
            Objects.requireNonNull(value, "Success value cannot be null");
        }

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public A getOrThrow() {
            return value;
        }

        @Override
        public A getOrElse(A defaultValue) {
            return value;
        }

        @Override
        public A getOrElse(Supplier<A> defaultSupplier) {
            return value;
        }

        @Override
        public Optional<Throwable> error() {
            return Optional.empty();
        }

        @Override
        public Optional<A> toOptional() {
            return Optional.of(value);
        }

        @Override
        public <B> Result<B> map(Function<A, B> f) {
            try {
                return success(f.apply(value));
            } catch (Throwable t) {
                return failure(t);
            }
        }

        @Override
        public <B> Result<B> flatMap(Function<A, Result<B>> f) {
            try {
                return f.apply(value);
            } catch (Throwable t) {
                return failure(t);
            }
        }

        @Override
        public <B> B fold(Function<Throwable, B> onFailure, Function<A, B> onSuccess) {
            return onSuccess.apply(value);
        }

        @Override
        public Result<A> onSuccess(Consumer<A> action) {
            action.accept(value);
            return this;
        }

        @Override
        public Result<A> onFailure(Consumer<Throwable> action) {
            return this;
        }

        @Override
        public Result<A> recover(Function<Throwable, A> f) {
            return this;
        }

        @Override
        public Result<A> recoverWith(Function<Throwable, Result<A>> f) {
            return this;
        }

        @Override
        public Result<A> orElse(Result<A> alternative) {
            return this;
        }

        @Override
        public Result<A> orElse(Supplier<Result<A>> alternative) {
            return this;
        }

        @Override
        public Result<A> filter(Predicate<A> predicate) {
            return predicate.test(value) ? this : failure(new java.util.NoSuchElementException("Predicate does not hold"));
        }

        @Override
        public Result<A> filterOrElse(Predicate<A> predicate, Function<A, Throwable> errorSupplier) {
            return predicate.test(value) ? this : failure(errorSupplier.apply(value));
        }

        @Override
        public Result<A> mapFailure(Function<Throwable, Throwable> f) {
            return this;
        }

        @Override
        public Result<Throwable> failed() {
            return failure(new UnsupportedOperationException("Success.failed"));
        }

        @Override
        public <B> Result<Pair<A, B>> zip(Result<B> other) {
            return other.map(b -> new Pair<>(value, b));
        }

        @Override
        public Result<A> andThen(ThrowingRunnable action) {
            try {
                action.run();
                return this;
            } catch (Throwable t) {
                return failure(t);
            }
        }
    }

    record Failure<A>(Throwable cause) implements Result<A> {
        public Failure {
            Objects.requireNonNull(cause, "Failure cause cannot be null");
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public A getOrThrow() {
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }

        @Override
        public A getOrElse(A defaultValue) {
            return defaultValue;
        }

        @Override
        public A getOrElse(Supplier<A> defaultSupplier) {
            return defaultSupplier.get();
        }

        @Override
        public Optional<Throwable> error() {
            return Optional.of(cause);
        }

        @Override
        public Optional<A> toOptional() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <B> Result<B> map(Function<A, B> f) {
            return (Result<B>) this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <B> Result<B> flatMap(Function<A, Result<B>> f) {
            return (Result<B>) this;
        }

        @Override
        public <B> B fold(Function<Throwable, B> onFailure, Function<A, B> onSuccess) {
            return onFailure.apply(cause);
        }

        @Override
        public Result<A> onSuccess(Consumer<A> action) {
            return this;
        }

        @Override
        public Result<A> onFailure(Consumer<Throwable> action) {
            action.accept(cause);
            return this;
        }

        @Override
        public Result<A> recover(Function<Throwable, A> f) {
            try {
                return success(f.apply(cause));
            } catch (Throwable t) {
                return failure(t);
            }
        }

        @Override
        public Result<A> recoverWith(Function<Throwable, Result<A>> f) {
            try {
                return f.apply(cause);
            } catch (Throwable t) {
                return failure(t);
            }
        }

        @Override
        public Result<A> orElse(Result<A> alternative) {
            return alternative;
        }

        @Override
        public Result<A> orElse(Supplier<Result<A>> alternative) {
            return alternative.get();
        }

        @Override
        public Result<A> filter(Predicate<A> predicate) {
            return this;
        }

        @Override
        public Result<A> filterOrElse(Predicate<A> predicate, Function<A, Throwable> errorSupplier) {
            return this;
        }

        @Override
        public Result<A> mapFailure(Function<Throwable, Throwable> f) {
            try {
                return failure(f.apply(cause));
            } catch (Throwable t) {
                return failure(t);
            }
        }

        @Override
        public Result<Throwable> failed() {
            return success(cause);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <B> Result<Pair<A, B>> zip(Result<B> other) {
            return (Result<Pair<A, B>>) this;
        }

        @Override
        public Result<A> andThen(ThrowingRunnable action) {
            return this;
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<A> {
        A get() throws Throwable;
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @FunctionalInterface
    interface ThrowingFunction<A, B> {
        B apply(A a) throws Throwable;
    }
}
