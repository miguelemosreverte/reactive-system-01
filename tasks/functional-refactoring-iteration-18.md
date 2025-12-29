# Functional Programming Refactoring - Iteration 18

**Goal**: More streams, Optional, immutable patterns
**Focus**: DiagnosticCollector, IdGenerator

---

## Task 1: DiagnosticCollector - Extract withStage/withDependency helpers

**File**: `platform/base/src/main/java/com/reactive/diagnostic/DiagnosticCollector.java`

Replace repeated null-check patterns with Optional-based helpers.

---

## Task 2: DiagnosticCollector - Stream for list building

Convert loops building lists to streams with `.toList()`.

---

## Task 3: DiagnosticCollector - Stream for thread counting

Convert null-check loop to stream filter/count.

---

## Task 4: IdGenerator - Extract hex encoding helper

Extract duplicated hex encoding to reusable method.

---

## Success Criteria

- [ ] All code compiles
- [ ] Reduced duplication
- [ ] More functional patterns
