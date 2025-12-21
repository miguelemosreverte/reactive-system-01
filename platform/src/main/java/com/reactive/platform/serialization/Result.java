package com.reactive.platform.serialization;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Functional Result type - Either[Throwable, A] in Scala terms.
 *
 * Represents either a success with a value, or a failure with an error.
 * Immutable and null-safe.
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
    }

    @FunctionalInterface
    interface ThrowingSupplier<A> {
        A get() throws Throwable;
    }
}
