/**
 * Serialization Module - Feature-flagged codec selection
 *
 * Development: JSON (human-readable, easy debugging)
 * Production: Avro (binary, compact, schema-validated, performant)
 *
 * Usage:
 *   import { codecs } from './platform/serialization';
 *   const encoded = codecs.counterEvent.encode(event);
 */

import { Codec, SerializationFormat, CounterEventWire, CounterResultWire, CounterAlertWire } from './types';
import { jsonCodecs } from './json-codec';

// Avro is optional - only import if available
let avroCodecs: typeof jsonCodecs | null = null;
try {
  avroCodecs = require('./avro-codec').default;
} catch (e) {
  // Avro not available, will use JSON
}

// ============================================================================
// Configuration
// ============================================================================

/**
 * Get serialization format from environment.
 * Default: 'json' for development, 'avro' for production
 */
export const getSerializationFormat = (): SerializationFormat => {
  const format = process.env.SERIALIZATION_FORMAT?.toLowerCase();

  if (format === 'avro') {
    if (!avroCodecs) {
      console.warn('Avro requested but avsc not installed. Falling back to JSON.');
      return 'json';
    }
    return 'avro';
  }

  if (format === 'json') {
    return 'json';
  }

  // Default based on NODE_ENV
  const isProduction = process.env.NODE_ENV === 'production';
  if (isProduction && avroCodecs) {
    return 'avro';
  }

  return 'json';
};

// ============================================================================
// Codec Selection
// ============================================================================

const format = getSerializationFormat();

/**
 * Active codecs based on current configuration.
 */
export const codecs: {
  readonly counterEvent: Codec<CounterEventWire>;
  readonly counterResult: Codec<CounterResultWire>;
  readonly counterAlert: Codec<CounterAlertWire>;
  readonly format: SerializationFormat;
} = format === 'avro' && avroCodecs
  ? { ...avroCodecs, format: 'avro' as const }
  : { ...jsonCodecs, format: 'json' as const };

// ============================================================================
// Benchmarking Support
// ============================================================================

/**
 * Get both codec implementations for benchmarking comparison.
 */
export const getAllCodecs = (): {
  json: typeof jsonCodecs;
  avro: typeof jsonCodecs | null;
} => ({
  json: jsonCodecs,
  avro: avroCodecs
});

/**
 * Benchmark a codec's encode/decode performance.
 * Returns operations per second.
 */
export const benchmarkCodec = <A>(
  codec: Codec<A>,
  sampleData: A,
  iterations: number = 10000
): { encodeOpsPerSec: number; decodeOpsPerSec: number; avgEncodeSizeBytes: number } => {
  // Warm up
  for (let i = 0; i < 100; i++) {
    const encoded = codec.encode(sampleData);
    if (encoded._tag === 'Right') {
      codec.decode(encoded.right);
    }
  }

  // Benchmark encode
  const encodeStart = process.hrtime.bigint();
  let totalSize = 0;
  const encodedBuffers: Buffer[] = [];

  for (let i = 0; i < iterations; i++) {
    const result = codec.encode(sampleData);
    if (result._tag === 'Right') {
      totalSize += result.right.length;
      encodedBuffers.push(result.right);
    }
  }

  const encodeEnd = process.hrtime.bigint();
  const encodeTimeMs = Number(encodeEnd - encodeStart) / 1_000_000;
  const encodeOpsPerSec = Math.round(iterations / (encodeTimeMs / 1000));
  const avgEncodeSizeBytes = Math.round(totalSize / iterations);

  // Benchmark decode
  const decodeStart = process.hrtime.bigint();

  for (const buffer of encodedBuffers) {
    codec.decode(buffer);
  }

  const decodeEnd = process.hrtime.bigint();
  const decodeTimeMs = Number(decodeEnd - decodeStart) / 1_000_000;
  const decodeOpsPerSec = Math.round(iterations / (decodeTimeMs / 1000));

  return {
    encodeOpsPerSec,
    decodeOpsPerSec,
    avgEncodeSizeBytes
  };
};

// ============================================================================
// Re-exports
// ============================================================================

export * from './types';
export { jsonCodecs } from './json-codec';

// Only export avro if available
if (avroCodecs) {
  module.exports.avroCodecs = avroCodecs;
}

console.log(`[Serialization] Using ${format.toUpperCase()} format`);
