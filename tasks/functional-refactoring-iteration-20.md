# Functional Programming Refactoring - Iteration 20

**Goal**: More streams, groupingBy, partitioningBy
**Focus**: TraceValidator

---

## Task 1: TraceValidator - Stream for service extraction (lines 137-145)

Convert loop extracting services from spans to stream with groupingBy.

---

## Task 2: TraceValidator - Extract key operation checker (lines 175-194)

Convert nested loop checking key operations to stream-based helper.

---

## Task 3: TraceValidator - Stream for validateMultiple (lines 244-255)

Convert loop to stream with partitioningBy.

---

## Success Criteria

- [ ] All code compiles
- [ ] Reduced nested loops
- [ ] More declarative patterns
