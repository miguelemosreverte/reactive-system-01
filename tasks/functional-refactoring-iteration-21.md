# Functional Programming Refactoring - Iteration 21

**Goal**: More streams, extract deduplication helper
**Focus**: ObservabilityFetcher

---

## Task 1: parseTrace - Stream for spans list (lines 160-172)

Convert for-loop building spans to stream.

---

## Task 2: parseTrace - Stream for processes map (lines 174-183)

Convert while loop with iterator to stream.

---

## Task 3: Extract deduplicateByLine helper (lines 229-250, 359-372)

Extract repeated deduplication pattern to reusable method.

---

## Task 4: enrichSampleEvents - Stream conversion (lines 384-400)

Convert loop with conditional to stream.

---

## Success Criteria

- [ ] All code compiles
- [ ] Reduced code duplication
- [ ] More declarative patterns
