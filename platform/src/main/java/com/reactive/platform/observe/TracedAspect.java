package com.reactive.platform.observe;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

/**
 * AOP Aspect that implements @Traced annotation behavior.
 *
 * For Spring: Add @Component to this class in your Spring configuration.
 * For AspectJ: Use compile-time or load-time weaving.
 *
 * Usage in Spring:
 *   @Configuration
 *   @EnableAspectJAutoProxy
 *   public class ObserveConfig {
 *       @Bean
 *       public TracedAspect tracedAspect() {
 *           return new TracedAspect();
 *       }
 *   }
 */
@Aspect
public class TracedAspect {

    @Around("@annotation(traced)")
    public Object traceMethod(ProceedingJoinPoint pjp, Traced traced) throws Throwable {
        if (!InvestigationContext.isActive()) {
            // Production mode: zero overhead
            return pjp.proceed();
        }

        // Determine operation name
        String operation = traced.value();
        if (operation.isEmpty()) {
            operation = pjp.getSignature().getName();
        }

        // Use Log.traced for consistent implementation
        final String op = operation;
        final Object[] result = new Object[1];
        final Throwable[] error = new Throwable[1];

        Log.traced(op, () -> {
            try {
                result[0] = pjp.proceed();
            } catch (Throwable t) {
                error[0] = t;
            }
        });

        if (error[0] != null) {
            throw error[0];
        }
        return result[0];
    }
}
