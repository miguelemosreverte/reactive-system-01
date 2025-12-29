# Functional Programming Refactoring - Iteration 22

**Goal**: Arrays.stream patterns for worker operations
**Focus**: BossWorkerHttpServer

---

## Task 1: requestCount() - Stream sum (lines 82-86)

Convert for-loop summing to Arrays.stream().mapToLong().sum()

---

## Task 2: perWorkerCounts() - IntStream pattern (lines 90-96)

Convert indexed for-loop to IntStream.range().mapToLong()

---

## Task 3: close() - Stream forEach for wakeup/join/cleanup (lines 102-116)

Convert multiple for-loops to stream forEach patterns

---

## Success Criteria

- [ ] All code compiles
- [ ] Cleaner worker array operations
- [ ] More declarative patterns
