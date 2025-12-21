package com.reactive.platform.benchmark;

import com.reactive.platform.schema.CounterEvent;
import com.reactive.platform.serialization.AvroCodec;
import com.reactive.platform.serialization.Codec;
import com.reactive.platform.serialization.JsonCodec;
import com.reactive.platform.serialization.Result;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * JMH Benchmark for serialization performance.
 *
 * Compares JSON vs Avro serialization.
 *
 * Run with: mvn test -Pbenchmark -Dtest=SerializationBenchmark
 * Or: java -jar target/benchmarks.jar SerializationBenchmark
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SerializationBenchmark {

    // JSON test data class
    public record TestEvent(
            String eventId,
            String sessionId,
            String action,
            int value,
            long timestamp
    ) {}

    // JSON codec
    private Codec<TestEvent> jsonCodec;
    private TestEvent jsonEvent;
    private byte[] jsonEncodedBytes;

    // Avro codec (uses generated CounterEvent)
    private Codec<CounterEvent> avroCodec;
    private CounterEvent avroEvent;
    private byte[] avroEncodedBytes;

    @Setup
    public void setup() {
        // JSON setup
        jsonCodec = JsonCodec.forClass(TestEvent.class);
        jsonEvent = new TestEvent(
                "evt-12345",
                "session-001",
                "increment",
                42,
                System.currentTimeMillis()
        );
        jsonEncodedBytes = jsonCodec.encode(jsonEvent).getOrThrow();

        // Avro setup (using generated Avro class)
        avroCodec = AvroCodec.forSpecific(CounterEvent.class);
        avroEvent = CounterEvent.newBuilder()
                .setEventId("evt-12345")
                .setSessionId("session-001")
                .setAction("increment")
                .setValue(42)
                .setTimestamp(System.currentTimeMillis())
                .build();
        avroEncodedBytes = avroCodec.encode(avroEvent).getOrThrow();

        // Print sizes for comparison
        System.out.printf("JSON size: %d bytes%n", jsonEncodedBytes.length);
        System.out.printf("Avro size: %d bytes%n", avroEncodedBytes.length);
    }

    // ========================================================================
    // JSON Benchmarks
    // ========================================================================

    @Benchmark
    public void jsonEncode(Blackhole bh) {
        Result<byte[]> result = jsonCodec.encode(jsonEvent);
        bh.consume(result);
    }

    @Benchmark
    public void jsonDecode(Blackhole bh) {
        Result<TestEvent> result = jsonCodec.decode(jsonEncodedBytes);
        bh.consume(result);
    }

    @Benchmark
    public void jsonRoundTrip(Blackhole bh) {
        byte[] encoded = jsonCodec.encode(jsonEvent).getOrThrow();
        TestEvent decoded = jsonCodec.decode(encoded).getOrThrow();
        bh.consume(decoded);
    }

    // ========================================================================
    // Avro Benchmarks
    // ========================================================================

    @Benchmark
    public void avroEncode(Blackhole bh) {
        Result<byte[]> result = avroCodec.encode(avroEvent);
        bh.consume(result);
    }

    @Benchmark
    public void avroDecode(Blackhole bh) {
        Result<CounterEvent> result = avroCodec.decode(avroEncodedBytes);
        bh.consume(result);
    }

    @Benchmark
    public void avroRoundTrip(Blackhole bh) {
        byte[] encoded = avroCodec.encode(avroEvent).getOrThrow();
        CounterEvent decoded = avroCodec.decode(encoded).getOrThrow();
        bh.consume(decoded);
    }

    /**
     * Run benchmark from command line.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SerializationBenchmark.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                .resultFormat(org.openjdk.jmh.results.format.ResultFormatType.JSON)
                .result("target/serialization-benchmark.json")
                .build();

        new Runner(opt).run();
    }
}
