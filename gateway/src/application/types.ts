/**
 * Application Types - Scala-style immutable data structures
 *
 * These are pure data types with no behavior, similar to Scala case classes.
 * All fields are readonly (immutable).
 */

// ============================================================================
// Functional Programming Primitives (à la Scala Option/Either)
// ============================================================================

export type Option<A> = Some<A> | None;

export interface Some<A> {
  readonly _tag: 'Some';
  readonly value: A;
}

export interface None {
  readonly _tag: 'None';
}

export const Some = <A>(value: A): Option<A> => ({ _tag: 'Some', value });
export const None: Option<never> = { _tag: 'None' };

export const isSome = <A>(opt: Option<A>): opt is Some<A> => opt._tag === 'Some';
export const isNone = <A>(opt: Option<A>): opt is None => opt._tag === 'None';

export const getOrElse = <A>(opt: Option<A>, defaultValue: A): A =>
  isSome(opt) ? opt.value : defaultValue;

export const map = <A, B>(opt: Option<A>, f: (a: A) => B): Option<B> =>
  isSome(opt) ? Some(f(opt.value)) : None;

export const flatMap = <A, B>(opt: Option<A>, f: (a: A) => Option<B>): Option<B> =>
  isSome(opt) ? f(opt.value) : None;

export type Either<L, R> = Left<L> | Right<R>;

export interface Left<L> {
  readonly _tag: 'Left';
  readonly left: L;
}

export interface Right<R> {
  readonly _tag: 'Right';
  readonly right: R;
}

export const Left = <L>(left: L): Either<L, never> => ({ _tag: 'Left', left });
export const Right = <R>(right: R): Either<never, R> => ({ _tag: 'Right', right });

export const isLeft = <L, R>(e: Either<L, R>): e is Left<L> => e._tag === 'Left';
export const isRight = <L, R>(e: Either<L, R>): e is Right<R> => e._tag === 'Right';

export const fold = <L, R, A>(
  e: Either<L, R>,
  onLeft: (l: L) => A,
  onRight: (r: R) => A
): A => (isLeft(e) ? onLeft(e.left) : onRight(e.right));

// ============================================================================
// Domain Types - Immutable Case Classes
// ============================================================================

/**
 * Counter Action ADT (Algebraic Data Type)
 * sealed trait CounterAction in Scala
 */
export type CounterAction =
  | { readonly _tag: 'Increment'; readonly value: number }
  | { readonly _tag: 'Decrement'; readonly value: number }
  | { readonly _tag: 'Set'; readonly value: number }
  | { readonly _tag: 'Reset' };

export const CounterAction = {
  Increment: (value: number): CounterAction => ({ _tag: 'Increment', value }),
  Decrement: (value: number): CounterAction => ({ _tag: 'Decrement', value }),
  Set: (value: number): CounterAction => ({ _tag: 'Set', value }),
  Reset: (): CounterAction => ({ _tag: 'Reset' }),

  fromString: (action: string, value: number): CounterAction => {
    switch (action.toUpperCase()) {
      case 'INCREMENT': return CounterAction.Increment(value);
      case 'DECREMENT': return CounterAction.Decrement(value);
      case 'SET': return CounterAction.Set(value);
      case 'RESET': return CounterAction.Reset();
      default: return CounterAction.Increment(value);
    }
  }
};

/**
 * Alert Level ADT
 * sealed trait AlertLevel in Scala
 */
export type AlertLevel =
  | { readonly _tag: 'None' }
  | { readonly _tag: 'Pending' }
  | { readonly _tag: 'Normal' }
  | { readonly _tag: 'LowRisk' }
  | { readonly _tag: 'MediumRisk' }
  | { readonly _tag: 'HighRisk' }
  | { readonly _tag: 'Critical' };

export const AlertLevel = {
  None: { _tag: 'None' } as AlertLevel,
  Pending: { _tag: 'Pending' } as AlertLevel,
  Normal: { _tag: 'Normal' } as AlertLevel,
  LowRisk: { _tag: 'LowRisk' } as AlertLevel,
  MediumRisk: { _tag: 'MediumRisk' } as AlertLevel,
  HighRisk: { _tag: 'HighRisk' } as AlertLevel,
  Critical: { _tag: 'Critical' } as AlertLevel,

  fromString: (s: string): AlertLevel => {
    switch (s.toUpperCase()) {
      case 'NONE': return AlertLevel.None;
      case 'PENDING': return AlertLevel.Pending;
      case 'NORMAL': return AlertLevel.Normal;
      case 'LOW_RISK': return AlertLevel.LowRisk;
      case 'MEDIUM_RISK': return AlertLevel.MediumRisk;
      case 'HIGH_RISK': return AlertLevel.HighRisk;
      case 'CRITICAL': return AlertLevel.Critical;
      default: return AlertLevel.None;
    }
  },

  toString: (level: AlertLevel): string => {
    switch (level._tag) {
      case 'None': return 'NONE';
      case 'Pending': return 'PENDING';
      case 'Normal': return 'NORMAL';
      case 'LowRisk': return 'LOW_RISK';
      case 'MediumRisk': return 'MEDIUM_RISK';
      case 'HighRisk': return 'HIGH_RISK';
      case 'Critical': return 'CRITICAL';
    }
  }
};

/**
 * Counter State - Immutable case class
 * case class CounterState(value: Int, alert: AlertLevel, message: String)
 */
export interface CounterState {
  readonly value: number;
  readonly alert: AlertLevel;
  readonly message: string;
}

export const CounterState = {
  empty: (): CounterState => ({
    value: 0,
    alert: AlertLevel.None,
    message: 'No events processed yet'
  }),

  create: (value: number, alert: AlertLevel, message: string): CounterState => ({
    value,
    alert,
    message
  }),

  // copy method like Scala case class
  copy: (state: CounterState, updates: Partial<CounterState>): CounterState => ({
    ...state,
    ...updates
  })
};

/**
 * Counter Event - Domain event for event sourcing
 * case class CounterEvent(...)
 */
export interface CounterEvent {
  readonly eventId: string;
  readonly sessionId: string;
  readonly action: CounterAction;
  readonly timestamp: number;
  readonly traceId: Option<string>;
}

export const CounterEvent = {
  create: (
    eventId: string,
    sessionId: string,
    action: CounterAction,
    timestamp: number,
    traceId: Option<string> = None
  ): CounterEvent => ({
    eventId,
    sessionId,
    action,
    timestamp,
    traceId
  })
};

/**
 * Counter Result - Result after processing
 * case class CounterResult(...)
 */
export interface CounterResult {
  readonly eventId: string;
  readonly sessionId: string;
  readonly previousValue: number;
  readonly currentValue: number;
  readonly alert: AlertLevel;
  readonly message: string;
  readonly processingTimeMs: number;
  readonly traceId: Option<string>;
}

export const CounterResult = {
  create: (
    eventId: string,
    sessionId: string,
    previousValue: number,
    currentValue: number,
    alert: AlertLevel,
    message: string,
    processingTimeMs: number,
    traceId: Option<string> = None
  ): CounterResult => ({
    eventId,
    sessionId,
    previousValue,
    currentValue,
    alert,
    message,
    processingTimeMs,
    traceId
  })
};

/**
 * Processing Timing - For observability
 */
export interface ProcessingTiming {
  readonly gatewayReceivedAt: number;
  readonly gatewayPublishedAt: Option<number>;
  readonly flinkReceivedAt: Option<number>;
  readonly flinkProcessedAt: Option<number>;
  readonly droolsStartAt: Option<number>;
  readonly droolsEndAt: Option<number>;
}

export const ProcessingTiming = {
  create: (gatewayReceivedAt: number): ProcessingTiming => ({
    gatewayReceivedAt,
    gatewayPublishedAt: None,
    flinkReceivedAt: None,
    flinkProcessedAt: None,
    droolsStartAt: None,
    droolsEndAt: None
  })
};
