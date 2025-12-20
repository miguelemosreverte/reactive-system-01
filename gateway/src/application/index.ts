/**
 * Application Layer - Pure Business Logic
 *
 * This module exports all application-level types and functions.
 * Everything here is pure (no I/O) and can be unit tested in isolation.
 *
 * Architecture:
 * - types.ts: Immutable data types (case classes)
 * - counter-fsm.ts: Pure state machine (transitions)
 */

// Re-export all types
export * from './types';

// Re-export FSM
export { CounterFSM, default as counterFSM } from './counter-fsm';
export {
  transition,
  applyAction,
  applyAlert,
  processEvent,
  validateEvent,
  actionToString,
  isInCriticalRange,
  suggestAlert,
  createInMemoryStore
} from './counter-fsm';

// Export type for state store
export type { StateStore } from './counter-fsm';
