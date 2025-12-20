/**
 * Avro Codec Implementation
 *
 * Production serialization format.
 * Binary, compact, schema-validated, and highly performant.
 *
 * Uses avsc library for Avro serialization.
 */

import * as avro from 'avsc';
import { Either, Left, Right } from '../../application/types';
import {
  Codec,
  SerializationError,
  CounterEventWire,
  CounterResultWire,
  CounterAlertWire
} from './types';

// ============================================================================
// Avro Schemas (Scala case class -> Avro record)
// ============================================================================

/**
 * Timing schema - embedded in events
 */
const TimingSchema = avro.Type.forSchema({
  type: 'record',
  name: 'Timing',
  namespace: 'com.reactive.events',
  fields: [
    { name: 'gatewayReceivedAt', type: ['null', 'long'], default: null },
    { name: 'gatewayPublishedAt', type: ['null', 'long'], default: null },
    { name: 'flinkReceivedAt', type: ['null', 'long'], default: null },
    { name: 'flinkProcessedAt', type: ['null', 'long'], default: null },
    { name: 'droolsStartAt', type: ['null', 'long'], default: null },
    { name: 'droolsEndAt', type: ['null', 'long'], default: null }
  ]
});

/**
 * CounterEvent schema
 */
const CounterEventSchema = avro.Type.forSchema({
  type: 'record',
  name: 'CounterEvent',
  namespace: 'com.reactive.events',
  fields: [
    { name: 'eventId', type: 'string' },
    { name: 'sessionId', type: 'string' },
    { name: 'action', type: 'string' },
    { name: 'value', type: 'int' },
    { name: 'timestamp', type: 'long' },
    { name: 'traceId', type: ['null', 'string'], default: null },
    {
      name: 'timing',
      type: ['null', {
        type: 'record',
        name: 'EventTiming',
        fields: [
          { name: 'gatewayReceivedAt', type: 'long' },
          { name: 'gatewayPublishedAt', type: ['null', 'long'], default: null }
        ]
      }],
      default: null
    }
  ]
});

/**
 * CounterResult schema
 */
const CounterResultSchema = avro.Type.forSchema({
  type: 'record',
  name: 'CounterResult',
  namespace: 'com.reactive.events',
  fields: [
    { name: 'eventId', type: 'string' },
    { name: 'sessionId', type: 'string' },
    { name: 'previousValue', type: 'int' },
    { name: 'currentValue', type: 'int' },
    { name: 'alert', type: 'string' },
    { name: 'message', type: 'string' },
    { name: 'processingTimeMs', type: 'long' },
    { name: 'traceId', type: ['null', 'string'], default: null },
    {
      name: 'timing',
      type: ['null', {
        type: 'record',
        name: 'ResultTiming',
        fields: [
          { name: 'gatewayReceivedAt', type: ['null', 'long'], default: null },
          { name: 'flinkReceivedAt', type: ['null', 'long'], default: null },
          { name: 'flinkProcessedAt', type: ['null', 'long'], default: null },
          { name: 'droolsStartAt', type: ['null', 'long'], default: null },
          { name: 'droolsEndAt', type: ['null', 'long'], default: null }
        ]
      }],
      default: null
    }
  ]
});

/**
 * CounterAlert schema
 */
const CounterAlertSchema = avro.Type.forSchema({
  type: 'record',
  name: 'CounterAlert',
  namespace: 'com.reactive.events',
  fields: [
    { name: 'eventId', type: 'string' },
    { name: 'sessionId', type: 'string' },
    { name: 'currentValue', type: 'int' },
    { name: 'alert', type: 'string' },
    { name: 'message', type: 'string' },
    { name: 'traceId', type: ['null', 'string'], default: null },
    {
      name: 'timing',
      type: ['null', {
        type: 'record',
        name: 'AlertTiming',
        fields: [
          { name: 'droolsStartAt', type: ['null', 'long'], default: null },
          { name: 'droolsEndAt', type: ['null', 'long'], default: null }
        ]
      }],
      default: null
    }
  ]
});

// ============================================================================
// Avro Codec Factory
// ============================================================================

/**
 * Create an Avro codec from a schema.
 */
export const createAvroCodec = <A>(
  name: string,
  schema: avro.Type
): Codec<A> => ({
  name,
  contentType: 'application/avro',

  encode: (a: A): Either<SerializationError, Buffer> => {
    try {
      const buffer = schema.toBuffer(a);
      return Right(buffer);
    } catch (e) {
      return Left(SerializationError.EncodeError(
        `Failed to encode ${name} to Avro`,
        e as Error
      ));
    }
  },

  decode: (bytes: Buffer): Either<SerializationError, A> => {
    try {
      const decoded = schema.fromBuffer(bytes) as A;
      return Right(decoded);
    } catch (e) {
      return Left(SerializationError.DecodeError(
        `Failed to decode ${name} from Avro`,
        e as Error
      ));
    }
  }
});

// ============================================================================
// Pre-built Avro Codecs for Domain Types
// ============================================================================

export const CounterEventAvroCodec: Codec<CounterEventWire> =
  createAvroCodec<CounterEventWire>('CounterEvent', CounterEventSchema);

export const CounterResultAvroCodec: Codec<CounterResultWire> =
  createAvroCodec<CounterResultWire>('CounterResult', CounterResultSchema);

export const CounterAlertAvroCodec: Codec<CounterAlertWire> =
  createAvroCodec<CounterAlertWire>('CounterAlert', CounterAlertSchema);

// ============================================================================
// Avro Codec Registry
// ============================================================================

export const avroCodecs = {
  counterEvent: CounterEventAvroCodec,
  counterResult: CounterResultAvroCodec,
  counterAlert: CounterAlertAvroCodec
};

// Export schemas for use in other services (Flink, etc.)
export const schemas = {
  counterEvent: CounterEventSchema,
  counterResult: CounterResultSchema,
  counterAlert: CounterAlertSchema,
  timing: TimingSchema
};

export default avroCodecs;
