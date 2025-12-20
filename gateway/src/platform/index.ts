/**
 * Platform Layer - Infrastructure Components
 *
 * This module exports all platform-level infrastructure.
 * These are the benchmarkable components that handle I/O.
 *
 * Architecture:
 * - serialization/: JSON/Avro codecs
 * - kafka/: Kafka client wrapper (TODO)
 * - http/: HTTP utilities (TODO)
 * - tracing/: OpenTelemetry (TODO)
 */

// Re-export serialization
export * from './serialization';
export { codecs, benchmarkCodec, getAllCodecs, getSerializationFormat } from './serialization';
