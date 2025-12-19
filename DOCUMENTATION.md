# Reactive System Documentation

## What Is This System?

This is a **demonstration of reactive, event-driven architecture** - a modern approach to building scalable, resilient systems. Instead of direct service-to-service calls, components communicate through events, enabling loose coupling and real-time responsiveness.

The system implements a simple counter application, but the architecture patterns apply to complex real-world scenarios like:
- Real-time analytics dashboards
- IoT sensor data processing
- Financial transaction monitoring
- E-commerce inventory and pricing updates

## Core Concepts

### Event-Driven Architecture

Traditional request-response systems have tight coupling:
```
Client → Service A → Service B → Service C → Response
```

Event-driven systems decouple producers from consumers:
```
Producer → Event Bus → Consumer 1
                    → Consumer 2
                    → Consumer 3
```

**Benefits:**
- **Scalability**: Add consumers without changing producers
- **Resilience**: If one consumer fails, others continue
- **Flexibility**: New features by adding new consumers
- **Auditability**: Event log provides complete history

### Stream Processing

Stream processing handles continuous data flows in real-time, unlike batch processing which works on fixed datasets.

**Batch Processing:**
```
Collect data for 1 hour → Process all at once → Output
```

**Stream Processing:**
```
Event arrives → Process immediately → Output instantly
```

Apache Flink provides:
- **Stateful processing**: Remember values across events
- **Exactly-once semantics**: No duplicate processing
- **Event time handling**: Process events by when they occurred
- **Windowing**: Aggregate events over time periods

### Business Rules Engine

Hard-coding business logic creates maintenance nightmares. Rules engines externalize logic:

**Hard-coded:**
```java
if (value > 100) {
    alert = "CRITICAL";
} else if (value > 10) {
    alert = "WARNING";
}
```

**Rules Engine (Drools):**
```drools
rule "Counter Critical"
when
    Counter(value > 100)
then
    modify($counter) { setAlert("CRITICAL") }
end
```

**Benefits:**
- Business users can modify rules without code changes
- Rules are version-controlled and auditable
- Complex rule interactions are handled automatically

## System Architecture

### The Complete Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                           USER INTERFACE                            │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  React UI (localhost:3000)                                   │   │
│  │  - Displays counter value and alert status                   │   │
│  │  - Sends user actions via WebSocket                          │   │
│  │  - Receives real-time updates                                │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ WebSocket
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         GATEWAY SERVICE                             │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Node.js Gateway (localhost:8080)                            │   │
│  │  - Manages WebSocket connections                             │   │
│  │  - Translates between WebSocket and Kafka                    │   │
│  │  - Maintains session state                                   │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
┌─────────────────────────────┐   ┌─────────────────────────────────┐
│      KAFKA (Event Bus)      │   │        KAFKA (Results)          │
│  ┌───────────────────────┐  │   │  ┌───────────────────────────┐  │
│  │  Topic: counter-events│  │   │  │  Topic: counter-results   │  │
│  │  - Stores all actions │  │   │  │  - Stores processed data  │  │
│  │  - Durable event log  │  │   │  │  - Consumed by Gateway    │  │
│  └───────────────────────┘  │   │  └───────────────────────────┘  │
└─────────────────────────────┘   └─────────────────────────────────┘
                    │                               ▲
                    │                               │
                    ▼                               │
┌─────────────────────────────────────────────────────────────────────┐
│                      FLINK (Stream Processor)                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Apache Flink (localhost:8081)                               │   │
│  │  - Consumes events from Kafka                                │   │
│  │  - Maintains per-session counter state                       │   │
│  │  - Applies counter operations (increment, decrement, set)    │   │
│  │  - Calls Drools for rule evaluation                          │   │
│  │  - Publishes results back to Kafka                           │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ REST API
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     DROOLS (Rules Engine)                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Drools Service (localhost:8180)                             │   │
│  │  - Evaluates business rules                                  │   │
│  │  - Determines alert level based on counter value             │   │
│  │  - Returns enriched response with alert and message          │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Step-by-Step Event Journey

**1. User Action**
```
User clicks [+] button in the UI
```

**2. WebSocket Message**
```json
{
  "type": "counter-action",
  "action": "increment",
  "value": 1
}
```

**3. Gateway Processing**
- Receives WebSocket message
- Adds session ID and timestamp
- Publishes to Kafka topic `counter-events`

**4. Kafka Event**
```json
{
  "sessionId": "abc-123",
  "action": "increment",
  "value": 1,
  "timestamp": 1702000000000
}
```

**5. Flink Processing**
- Reads event from Kafka
- Looks up current counter state for session (e.g., 10)
- Applies operation: 10 + 1 = 11
- Updates state
- Calls Drools REST API with new value

**6. Drools Evaluation**
- Receives value: 11
- Matches rule: "Counter Warning" (value 11-100)
- Returns:
```json
{
  "value": 11,
  "alert": "WARNING",
  "message": "Counter value is elevated"
}
```

**7. Flink Result**
- Combines state with Drools response
- Publishes to Kafka topic `counter-results`

**8. Gateway Broadcast**
- Consumes from `counter-results`
- Finds WebSocket connection for session
- Sends update:
```json
{
  "type": "counter-update",
  "data": {
    "currentValue": 11,
    "alert": "WARNING",
    "message": "Counter value is elevated"
  }
}
```

**9. UI Update**
- Receives WebSocket message
- Updates React state
- Re-renders with new value and alert color

**Total latency: ~50-200ms** (depending on system load)

## Business Rules

The Drools rules engine evaluates counter values and assigns alert levels:

| Value Range | Alert Level | Message | Color |
|-------------|-------------|---------|-------|
| `< 0` | INVALID | Counter value is invalid (negative) | Red |
| `= 0` | RESET | Counter has been reset | Gray |
| `1 - 10` | NORMAL | Counter value is within normal range | Green |
| `11 - 100` | WARNING | Counter value is elevated | Yellow |
| `> 100` | CRITICAL | Counter value is critical | Red |

### Rule Priority (Salience)

Rules have priorities to handle overlapping conditions:
1. **CRITICAL** (highest) - checked first
2. **WARNING**
3. **NORMAL**
4. **RESET**
5. **INVALID** (lowest)

## Technology Deep Dive

### Apache Kafka

Kafka is a distributed event streaming platform:

- **Topics**: Named channels for events (like `counter-events`)
- **Partitions**: Enable parallel processing
- **Consumer Groups**: Allow multiple consumers to share work
- **Retention**: Events are stored durably (configurable time)

**In This System:**
- `counter-events`: User actions from Gateway
- `counter-results`: Processed results from Flink

### Apache Flink

Flink is a stateful stream processing framework:

- **DataStream API**: Process unbounded event streams
- **Keyed State**: Maintain state per key (session ID)
- **Checkpointing**: Fault-tolerant state snapshots
- **Event Time**: Process based on when events occurred

**In This System:**
- Keyed by `sessionId` for per-user state
- Uses `ValueState<Integer>` for counter value
- Processes events as they arrive (no batching)

### Drools

Drools is a Business Rules Management System (BRMS):

- **DRL Files**: Declarative rule language
- **Pattern Matching**: Match facts against conditions
- **Inference Engine**: Automatically re-evaluate when facts change

**In This System:**
- Rules defined in `counter-rules.drl`
- REST API for synchronous evaluation
- Stateless evaluation (no session persistence)

## Scaling Considerations

### Current Setup (Development)
- Single instance of each service
- Single Kafka partition
- No replication

### Production Scaling

**Kafka:**
- Multiple partitions for parallelism
- Replication factor 3 for durability
- Multiple brokers for availability

**Flink:**
- Multiple TaskManagers
- Increased parallelism
- Checkpointing to distributed storage

**Gateway:**
- Multiple instances behind load balancer
- Sticky sessions or shared session store
- Redis for shared state

**Drools:**
- Multiple instances (stateless)
- Load balancer distribution

## Further Reading

- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Apache Flink Documentation](https://flink.apache.org/docs/stable/)
- [Drools Documentation](https://www.drools.org/learn/documentation.html)
- [Event-Driven Architecture Patterns](https://martinfowler.com/articles/201701-event-driven.html)

## Component Documentation

For detailed information about each component, see:

- [UI Documentation](./ui/DOCUMENTATION.md) - React frontend
- [Gateway Documentation](./gateway/DOCUMENTATION.md) - WebSocket bridge
- [Flink Documentation](./flink/DOCUMENTATION.md) - Stream processing
- [Drools Documentation](./drools/DOCUMENTATION.md) - Business rules
