/**
 * JSON Codec Implementation
 *
 * Default serialization format for development.
 * Human-readable, easy to debug, but less performant than Avro.
 */

import { Either, Left, Right } from '../../application/types';
import {
  Codec,
  SerializationError,
  CounterEventWire,
  CounterResultWire,
  CounterAlertWire
} from './types';

// ============================================================================
// JSON Codec Factory (Generic)
// ============================================================================

/**
 * Create a JSON codec for any type.
 * Uses TypeScript's structural typing - no schema validation.
 */
export const createJsonCodec = <A>(name: string): Codec<A> => ({
  name,
  contentType: 'application/json',

  encode: (a: A): Either<SerializationError, Buffer> => {
    try {
      const json = JSON.stringify(a);
      return Right(Buffer.from(json, 'utf-8'));
    } catch (e) {
      return Left(SerializationError.EncodeError(
        `Failed to encode ${name} to JSON`,
        e as Error
      ));
    }
  },

  decode: (bytes: Buffer): Either<SerializationError, A> => {
    try {
      const json = bytes.toString('utf-8');
      const parsed = JSON.parse(json) as A;
      return Right(parsed);
    } catch (e) {
      return Left(SerializationError.DecodeError(
        `Failed to decode ${name} from JSON`,
        e as Error
      ));
    }
  }
});

// ============================================================================
// Pre-built JSON Codecs for Domain Types
// ============================================================================

export const CounterEventJsonCodec: Codec<CounterEventWire> =
  createJsonCodec<CounterEventWire>('CounterEvent');

export const CounterResultJsonCodec: Codec<CounterResultWire> =
  createJsonCodec<CounterResultWire>('CounterResult');

export const CounterAlertJsonCodec: Codec<CounterAlertWire> =
  createJsonCodec<CounterAlertWire>('CounterAlert');

// ============================================================================
// JSON Codec Registry
// ============================================================================

export const jsonCodecs = {
  counterEvent: CounterEventJsonCodec,
  counterResult: CounterResultJsonCodec,
  counterAlert: CounterAlertJsonCodec
};

export default jsonCodecs;
