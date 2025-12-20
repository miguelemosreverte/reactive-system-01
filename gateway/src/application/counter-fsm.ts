/**
 * Counter FSM - Pure Finite State Machine
 *
 * This module contains ONLY pure functions with NO side effects.
 * It's the heart of the business logic, completely isolated from infrastructure.
 *
 * In Scala terms, this would be a pure object with methods that are referentially transparent.
 *
 * Usage:
 *   const newState = CounterFSM.transition(currentState, event)
 *
 * Properties:
 *   - Deterministic: same input always produces same output
 *   - No I/O: no network, no disk, no randomness
 *   - Testable: unit test in milliseconds
 *   - Benchmarkable: measure millions of transitions per second
 */

import {
  CounterState,
  CounterEvent,
  CounterAction,
  AlertLevel,
  CounterResult,
  Option,
  Some,
  None,
  Either,
  Left,
  Right,
  isSome,
  getOrElse
} from './types';

// ============================================================================
// State Transition Functions (Pure)
// ============================================================================

/**
 * Apply an action to compute the new counter value.
 * Pure function: (value, action) => newValue
 */
export const applyAction = (currentValue: number, action: CounterAction): number => {
  switch (action._tag) {
    case 'Increment':
      return currentValue + action.value;
    case 'Decrement':
      return currentValue - action.value;
    case 'Set':
      return action.value;
    case 'Reset':
      return 0;
  }
};

/**
 * Main state transition function.
 * Pure function: (state, event) => newState
 *
 * This is the core FSM transition. The alert is set to Pending
 * because the actual alert evaluation happens asynchronously in Drools.
 */
export const transition = (
  state: CounterState,
  event: CounterEvent
): CounterState => {
  const newValue = applyAction(state.value, event.action);

  return CounterState.copy(state, {
    value: newValue,
    alert: AlertLevel.Pending,
    message: `${actionToString(event.action)} applied`
  });
};

/**
 * Update state with alert from Drools evaluation.
 * Pure function: (state, alert, message) => newState
 */
export const applyAlert = (
  state: CounterState,
  alert: AlertLevel,
  message: string
): CounterState => {
  return CounterState.copy(state, {
    alert,
    message
  });
};

// ============================================================================
// Event Processing (Pure)
// ============================================================================

/**
 * Process an event and produce a result.
 * Pure function: (state, event) => (newState, result)
 *
 * Returns a tuple of the new state and the result to be published.
 */
export const processEvent = (
  state: CounterState,
  event: CounterEvent
): [CounterState, CounterResult] => {
  const previousValue = state.value;
  const newState = transition(state, event);
  const processingTimeMs = Date.now() - event.timestamp;

  const result = CounterResult.create(
    event.eventId,
    event.sessionId,
    previousValue,
    newState.value,
    newState.alert,
    newState.message,
    processingTimeMs,
    event.traceId
  );

  return [newState, result];
};

// ============================================================================
// Validation (Pure)
// ============================================================================

export type ValidationError =
  | { readonly _tag: 'InvalidAction'; readonly action: string }
  | { readonly _tag: 'InvalidValue'; readonly value: number; readonly reason: string }
  | { readonly _tag: 'InvalidSessionId'; readonly sessionId: string };

export const ValidationError = {
  InvalidAction: (action: string): ValidationError =>
    ({ _tag: 'InvalidAction', action }),
  InvalidValue: (value: number, reason: string): ValidationError =>
    ({ _tag: 'InvalidValue', value, reason }),
  InvalidSessionId: (sessionId: string): ValidationError =>
    ({ _tag: 'InvalidSessionId', sessionId })
};

/**
 * Validate event data before processing.
 * Pure function: input => Either[ValidationError, ValidatedInput]
 */
export const validateEvent = (
  sessionId: string,
  action: string,
  value: number
): Either<ValidationError, { sessionId: string; action: CounterAction }> => {
  // Validate session ID
  if (!sessionId || sessionId.trim() === '') {
    return Left(ValidationError.InvalidSessionId(sessionId));
  }

  // Validate value
  if (!Number.isFinite(value)) {
    return Left(ValidationError.InvalidValue(value, 'Value must be a finite number'));
  }

  if (value < -1_000_000 || value > 1_000_000) {
    return Left(ValidationError.InvalidValue(value, 'Value must be between -1,000,000 and 1,000,000'));
  }

  // Parse action
  const parsedAction = CounterAction.fromString(action, value);

  return Right({
    sessionId: sessionId.trim(),
    action: parsedAction
  });
};

// ============================================================================
// Helper Functions (Pure)
// ============================================================================

/**
 * Convert action to human-readable string.
 */
export const actionToString = (action: CounterAction): string => {
  switch (action._tag) {
    case 'Increment':
      return `Increment by ${action.value}`;
    case 'Decrement':
      return `Decrement by ${action.value}`;
    case 'Set':
      return `Set to ${action.value}`;
    case 'Reset':
      return 'Reset to 0';
  }
};

/**
 * Check if the counter value is in a critical range.
 * This is a pure predicate for quick local checks.
 * The actual alert evaluation happens in Drools.
 */
export const isInCriticalRange = (value: number): boolean => {
  return value > 100 || value < -100;
};

/**
 * Compute suggested alert based on value (local heuristic).
 * The authoritative alert comes from Drools.
 */
export const suggestAlert = (value: number): AlertLevel => {
  const absValue = Math.abs(value);
  if (absValue === 0) return AlertLevel.Normal;
  if (absValue <= 10) return AlertLevel.Normal;
  if (absValue <= 50) return AlertLevel.LowRisk;
  if (absValue <= 100) return AlertLevel.MediumRisk;
  if (absValue <= 500) return AlertLevel.HighRisk;
  return AlertLevel.Critical;
};

// ============================================================================
// State Store Operations (Pure interfaces)
// ============================================================================

/**
 * State store interface - defines what operations are needed.
 * Implementation will be in the platform layer.
 */
export interface StateStore<K, V> {
  readonly get: (key: K) => Option<V>;
  readonly set: (key: K, value: V) => void;
  readonly delete: (key: K) => boolean;
  readonly has: (key: K) => boolean;
  readonly size: () => number;
}

/**
 * Create an in-memory state store (for development/testing).
 * Pure factory function.
 */
export const createInMemoryStore = <K, V>(): StateStore<K, V> => {
  const store = new Map<K, V>();

  return {
    get: (key: K): Option<V> => {
      const value = store.get(key);
      return value !== undefined ? Some(value) : None;
    },
    set: (key: K, value: V): void => {
      store.set(key, value);
    },
    delete: (key: K): boolean => store.delete(key),
    has: (key: K): boolean => store.has(key),
    size: (): number => store.size
  };
};

// ============================================================================
// Export the FSM as a namespace (Scala object style)
// ============================================================================

export const CounterFSM = {
  // State transitions
  transition,
  applyAction,
  applyAlert,
  processEvent,

  // Validation
  validateEvent,

  // Helpers
  actionToString,
  isInCriticalRange,
  suggestAlert,

  // Store factory
  createStore: createInMemoryStore<string, CounterState>
};

export default CounterFSM;
