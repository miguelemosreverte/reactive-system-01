# TODO

Tasks organized by component. **Tasks in different components can run in parallel.**

---

## Parallel Group A: Core Libraries

### Platform (`platform/src/`)
- [ ] **Logging abstraction** - Create `Logging.java` similar to `Tracing.java`
  - Hide SLF4J types behind clean static API
  - `Logging.info()`, `Logging.error()`, etc.
- [ ] **Null audit** - Find and wrap all null usage
- [ ] **Dead code removal** - Identify unused classes/methods
- [ ] **Type propagation audit** - Ensure no third-party types leak

### CLI (`platform/cli/`)
- [ ] **Dead code removal** - Unused commands or flags
- [ ] **Error handling** - Use Result pattern instead of panics
- [ ] **Null/nil audit** - Proper Go error handling

---

## Parallel Group B: Services

### Application (`application/src/`)
- [ ] **Use Logging abstraction** - Replace direct SLF4J usage
- [ ] **Null audit** - Wrap third-party nulls
- [ ] **Controller cleanup** - Pure business logic only

### Flink (`platform/deployment/docker/flink/`)
- [ ] **Use Logging abstraction** - Replace direct SLF4J usage
- [ ] **Simplify CounterProcessor** - Remove remaining OTel ceremony
- [ ] **Null audit** - Event deserialization edge cases

### Drools (`platform/deployment/docker/drools/`)
- [ ] **Use Logging abstraction** - Replace direct SLF4J usage
- [ ] **Null audit** - Rule evaluation edge cases

### Gateway (`platform/deployment/docker/gateway/`)
- [ ] **Use Logging abstraction** - Replace direct SLF4J usage
- [ ] **Null audit** - Kafka message handling

---

## Parallel Group C: Frontend

### UI (`ui/src/`)
- [ ] **TypeScript strict null checks** - Enable in tsconfig
- [ ] **Dead code removal** - Unused components

---

## Parallel Group D: Benchmarking

### Performance (any component)
- [ ] **Baseline capture** - Document current throughput/latency
- [ ] **Bottleneck analysis** - Use CLI diagnostics
- [ ] **Optimization cycle** - Implement workflow 2

---

## Parallelization Matrix

| Task Area | Can Parallel With |
|-----------|-------------------|
| Platform | CLI, Application, Flink, Drools, Gateway, UI |
| CLI | Platform, Application, Flink, Drools, Gateway, UI |
| Application | Platform, CLI, Flink, Drools, Gateway, UI |
| Flink | Platform, CLI, Application, Drools, Gateway, UI |
| Drools | Platform, CLI, Application, Flink, Gateway, UI |
| Gateway | Platform, CLI, Application, Flink, Drools, UI |
| UI | All backend components |

**Note:** Performance work on a specific component should not parallel with refactoring that same component.
