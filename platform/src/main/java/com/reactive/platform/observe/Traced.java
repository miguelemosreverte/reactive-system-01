package com.reactive.platform.observe;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic debug logging and tracing.
 *
 * When investigation mode is active:
 *   [DEBUG] → operation-name
 *   [DEBUG] ← operation-name (45ms)
 *   + Creates span in Jaeger with parent context
 *
 * When investigation mode is inactive:
 *   Method executes normally with zero overhead.
 *
 * Usage:
 *   @Traced("process-order")
 *   public Result process(Order order) {
 *       // Pure business logic
 *       return Result.success(order.complete());
 *   }
 *
 *   // Or let it infer name from method:
 *   @Traced
 *   public Result processOrder(Order order) {
 *       // Span name will be "processOrder"
 *   }
 *
 * Requires TracedAspect to be active (Spring AOP or AspectJ).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Traced {

    /**
     * Operation name for the span and logs.
     * If empty, uses the method name.
     */
    String value() default "";
}
