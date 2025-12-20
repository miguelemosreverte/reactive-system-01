/**
 * Serialization Types - Codec abstraction for JSON/Avro
 *
 * This module provides a Scala-like type class pattern for serialization.
 * Implementations can be swapped between JSON (development) and Avro (production).
 */

import { Either, Left, Right } from '../../application/types';

// ============================================================================
// Codec Type Class (à la Scala implicit type classes)
// ============================================================================

/**
 * Serialization error ADT
 */
export type SerializationError =
  | { readonly _tag: 'EncodeError'; readonly message: string; readonly cause?: Error }
  | { readonly _tag: 'DecodeError'; readonly message: string; readonly cause?: Error }
  | { readonly _tag: 'SchemaError'; readonly message: string; readonly cause?: Error };

export const SerializationError = {
  EncodeError: (message: string, cause?: Error): SerializationError =>
    ({ _tag: 'EncodeError', message, cause }),
  DecodeError: (message: string, cause?: Error): SerializationError =>
    ({ _tag: 'DecodeError', message, cause }),
  SchemaError: (message: string, cause?: Error): SerializationError =>
    ({ _tag: 'SchemaError', message, cause })
};

/**
 * Codec type class - like Scala's Codec[A]
 *
 * trait Codec[A] {
 *   def encode(a: A): Either[SerializationError, Buffer]
 *   def decode(bytes: Buffer): Either[SerializationError, A]
 * }
 */
export interface Codec<A> {
  readonly encode: (a: A) => Either<SerializationError, Buffer>;
  readonly decode: (bytes: Buffer) => Either<SerializationError, A>;
  readonly contentType: string;
  readonly name: string;
}

/**
 * Serialization format enum
 */
export type SerializationFormat = 'json' | 'avro';

/**
 * Codec registry for managing multiple codecs
 */
export interface CodecRegistry {
  readonly register: <A>(name: string, codec: Codec<A>) => void;
  readonly get: <A>(name: string) => Codec<A> | undefined;
  readonly format: SerializationFormat;
}

// ============================================================================
// Wire Format Types (for Kafka messages)
// ============================================================================

/**
 * Counter Event Wire Format
 * This is what gets serialized to Kafka
 */
export interface CounterEventWire {
  readonly eventId: string;
  readonly sessionId: string;
  readonly action: string;
  readonly value: number;
  readonly timestamp: number;
  readonly traceId: string | null;
  readonly timing: {
    readonly gatewayReceivedAt: number;
    readonly gatewayPublishedAt?: number;
  } | null;
}

/**
 * Counter Result Wire Format
 */
export interface CounterResultWire {
  readonly eventId: string;
  readonly sessionId: string;
  readonly previousValue: number;
  readonly currentValue: number;
  readonly alert: string;
  readonly message: string;
  readonly processingTimeMs: number;
  readonly traceId: string | null;
  readonly timing: {
    readonly gatewayReceivedAt?: number;
    readonly flinkReceivedAt?: number;
    readonly flinkProcessedAt?: number;
    readonly droolsStartAt?: number;
    readonly droolsEndAt?: number;
  } | null;
}

/**
 * Counter Alert Wire Format (from Drools)
 */
export interface CounterAlertWire {
  readonly eventId: string;
  readonly sessionId: string;
  readonly currentValue: number;
  readonly alert: string;
  readonly message: string;
  readonly traceId: string | null;
  readonly timing: {
    readonly droolsStartAt?: number;
    readonly droolsEndAt?: number;
  } | null;
}
