# Functional Cleanup Tasks

## Summary
- **try-catch blocks remaining**: ~300 (outside base module)
  - try-with-resources: 99 (OK - Java idiom for AutoCloseable)
  - ~~InterruptedException: 71~~ → Mostly converted with `Result.sleep()`, `Result.join()` ✅
  - ~~catch ignored: 31~~ ✅ DONE
  - Remaining benchmarks: ~6 (complex control flow with latches)
  - other: ~180
- **null patterns**: 327 (outside base module)

---

## 1. IGNORED CATCHES ✅ COMPLETED

All 31 `catch.*ignored` patterns converted to `Result.run()` or `Result.of()`.

### http-server module
| File | Line | Pattern |
|------|------|---------|
| http-server/src/.../TurboHttpServer.java | 278 | `catch (IOException ignored)` |
| http-server/src/.../UltraFastHttpServer.java | 238 | `catch (IOException ignored)` |
| http-server/src/.../UltraFastHttpServer.java | 272 | `catch (Exception ignored)` |
| http-server/src/.../RocketHttpServer.java | 227 | `catch (IOException ignored)` |
| http-server/src/.../RocketHttpServer.java | 388 | `catch (IOException ignored)` |
| http-server/src/.../RawHttpServer.java | 634 | `catch (IOException ignored)` |
| http-server/src/.../UltraHttpServer.java | 246 | `catch (IOException ignored)` |
| http-server/src/.../FastHttpServer.java | 280 | `catch (IOException ignored)` |
| http-server/src/.../HyperHttpServer.java | 194 | `catch (Exception ignored)` |
| http-server/src/.../HyperHttpServer.java | 235 | `catch (ClosedChannelException ignored)` |
| http-server/src/.../HyperHttpServer.java | 405 | `catch (IOException ignored)` |

### http-server/benchmarks
| File | Line | Pattern |
|------|------|---------|
| benchmarks/.../UnifiedHttpBenchmark.java | 277 | `catch (NoSuchMethodException ignored)` |
| benchmarks/.../UnifiedHttpBenchmark.java | 283 | `catch (NoSuchMethodException ignored)` |
| benchmarks/.../UnifiedHttpBenchmark.java | 298 | `catch (NoSuchMethodException ignored)` |

### platform/src duplicates
| File | Line | Pattern |
|------|------|---------|
| src/.../benchmark/ScalingDiagnostics.java | 379 | `catch (InterruptedException ignored)` |
| src/.../benchmark/ScalingDiagnostics.java | 508 | `catch (InterruptedException ignored)` |
| src/.../benchmark/ObservabilityFetcher.java | 303 | `catch (Exception ignored)` |
| src/.../benchmark/UnifiedHttpBenchmark.java | 277 | `catch (NoSuchMethodException ignored)` |
| src/.../benchmark/UnifiedHttpBenchmark.java | 283 | `catch (NoSuchMethodException ignored)` |
| src/.../benchmark/UnifiedHttpBenchmark.java | 298 | `catch (NoSuchMethodException ignored)` |
| src/.../http/TurboHttpServer.java | 278 | `catch (IOException ignored)` |
| src/.../http/UltraFastHttpServer.java | 238 | `catch (IOException ignored)` |
| src/.../http/UltraFastHttpServer.java | 272 | `catch (Exception ignored)` |
| src/.../http/RocketHttpServer.java | 227 | `catch (IOException ignored)` |
| src/.../http/RocketHttpServer.java | 388 | `catch (IOException ignored)` |
| src/.../http/RawHttpServer.java | 634 | `catch (IOException ignored)` |
| src/.../http/UltraHttpServer.java | 246 | `catch (IOException ignored)` |
| src/.../http/FastHttpServer.java | 280 | `catch (IOException ignored)` |
| src/.../http/HyperHttpServer.java | 194 | `catch (Exception ignored)` |
| src/.../http/HyperHttpServer.java | 235 | `catch (ClosedChannelException ignored)` |
| src/.../http/HyperHttpServer.java | 405 | `catch (IOException ignored)` |

---

## 2. INTERRUPTEDEXCEPTION ✅ MOSTLY COMPLETED

Added to Result.java:
- `Result.sleep(long millis)` - sleep with interrupt restoration
- `Result.sleep(long millis, int nanos)` - nanosecond precision
- `Result.sleep(Duration)` - duration-based
- `Result.join(Thread, long)` - thread join with timeout
- `Result.join(Thread)` - thread join without timeout

Converted ~65 patterns. Remaining 6 are complex benchmark patterns with await/latch control flow.

---

## 3. TRY-FINALLY PATTERNS

Used for cleanup with guaranteed execution. Consider:
- Keep as-is (no Result equivalent for finally)
- Or add `Result.guarantee(action, cleanup)` helper

---

## 4. NULL PATTERNS ✅ AUDITED

After analysis, ~227 null patterns found outside base module. Most should remain as-is.

### Categories (KEEP as-is):

| Category | Count | Reason |
|----------|-------|--------|
| NIO accept/poll loops | ~40 | Idiomatic Java: `while ((client = serverChannel.accept()) != null)` |
| Queue drain patterns | ~25 | Idiomatic Java: `while ((item = queue.poll()) != null)` |
| Kafka callback exceptions | ~15 | API requirement: `if (exception != null)` |
| Lazy initialization | ~10 | Thread-safe singleton: `if (instance == null)` |
| Builder validation | ~8 | Preconditions: `if (codec == null) throw new IllegalStateException(...)` |
| Mutable variable init | ~20 | Algorithm state: `Config bestConfig = null;` |

### Categories (OPTIONAL conversion possible but LOW VALUE):

| Category | Count | Notes |
|----------|-------|-------|
| Handler lookups | ~6 | `if (handler == null) return Response.notFound()` - could wrap in Optional |
| Early returns | ~10 | `if (result == null) return;` - could return Optional |

### Conclusion

Converting these patterns to Optional would add overhead in hot paths (NIO loops, queue drains)
and reduce readability for idiomatic Java patterns. The cost/benefit ratio is unfavorable.

**Recommendation**: Keep null patterns as-is. They are:
1. Framework/API requirements (NIO, Kafka)
2. Performance-critical hot paths
3. Standard Java idioms that any Java developer understands

### Command to find:
```bash
grep -rn "= null\|== null\|!= null" --include="*.java" platform/ | grep -v "/target/" | grep -v "platform/base/"
```

---

## Execution Order

1. ~~**Phase 1**: Convert all `catch.*ignored` patterns (31 items)~~ ✅ COMPLETED
2. ~~**Phase 2**: Add `Result.sleep()` helper, convert InterruptedException~~ ✅ COMPLETED
3. ~~**Phase 3**: Audit null patterns~~ ✅ AUDITED (keep as-is - idiomatic Java patterns)
4. ~~**Phase 4**: Review remaining try-catch for Result.of() opportunities~~ ✅ AUDITED

## Phase 4 Notes

Converted additional InterruptedException patterns in Phase 4:
- MicrobatchCollector.java: 5 patterns → `Result.sleep()`, `Result.join()`
- MicrobatchingGateway.java: 2 patterns → `Result.sleep()`, `Result.run()`
- FastGateway.java: 1 pattern → `Result.run()`
- HyperHttpServer.java: 1 pattern → `Result.run()`
- RocketHttpServer.java: 2 patterns → `Result.join()`, `Result.run()`
- UltraHttpServer.java: 1 pattern → `Result.join()`
- RawHttpServer.java: 1 pattern → `Result.run()`
- ZeroCopyHttpServer.java: 1 pattern → `Result.join()`
- TurboHttpServer.java: 1 pattern → `Result.join()`
- BossWorkerHttpServer.java: 1 pattern → `Result.join()`

Remaining 11 InterruptedException patterns (intentionally kept):
- Benchmark files with await/latch control flow (8 patterns)
- NettyHttpServer startup failure (1) - throws RuntimeException
- ObservabilityFetcher (1) - complex retry logic
- KafkaProducerBenchmark (2) - latch await patterns

These remaining patterns have complex control flow that doesn't map cleanly to Result semantics.
