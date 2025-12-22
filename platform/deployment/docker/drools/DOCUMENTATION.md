# Drools Component Documentation

## Overview

The Drools service is a business rules engine that evaluates counter values and determines alert levels. It externalizes business logic from application code, enabling non-developers to modify rules without code changes.

## Technology Stack

| Technology | Purpose |
|------------|---------|
| Drools 8.x | Business rules engine |
| Spring Boot 3.x | Application framework |
| Java 17 | Runtime environment |
| Maven | Build and dependency management |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                       Drools Service                            │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  RuleController.java                     │   │
│  │  - REST API endpoints                                    │   │
│  │  - Request/response handling                             │   │
│  │  - Input validation                                      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   RuleService.java                       │   │
│  │  - Creates KieSession per request                        │   │
│  │  - Inserts facts (Counter object)                        │   │
│  │  - Fires rules                                           │   │
│  │  - Extracts results                                      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                 counter-rules.drl                        │   │
│  │  - Rule definitions in DRL syntax                        │   │
│  │  - Pattern matching on Counter                           │   │
│  │  - Alert level assignment                                │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Key Files

### `src/main/resources/rules/counter-rules.drl`
Rule definitions in Drools Rule Language:

```drools
package com.reactive.drools.rules

import com.reactive.drools.model.Counter

// Rule 1: Normal range (1-10)
rule "Counter Normal"
    salience 2
    when
        $counter : Counter(value >= 1, value <= 10)
    then
        modify($counter) { setAlert("NORMAL") }
end

// Rule 2: Warning range (11-100)
rule "Counter Warning"
    salience 3
    when
        $counter : Counter(value >= 11, value <= 100)
    then
        modify($counter) { setAlert("WARNING") }
end

// Rule 3: Critical (>100)
rule "Counter Critical"
    salience 4
    when
        $counter : Counter(value > 100)
    then
        modify($counter) { setAlert("CRITICAL") }
end

// Rule 4: Reset (0)
rule "Counter Reset"
    salience 1
    when
        $counter : Counter(value == 0)
    then
        modify($counter) { setAlert("RESET") }
end

// Rule 5: Invalid (<0)
rule "Counter Negative"
    salience 5
    when
        $counter : Counter(value < 0)
    then
        modify($counter) { setAlert("INVALID") }
end
```

### `src/main/java/com/reactive/drools/service/RuleService.java`
Rule evaluation service:

```java
@Service
public class RuleService {

    private final KieContainer kieContainer;

    public RuleService(KieContainer kieContainer) {
        this.kieContainer = kieContainer;
    }

    public EvaluationResponse evaluate(int value) {
        // 1. Create new session (stateless evaluation)
        KieSession session = kieContainer.newKieSession();

        try {
            // 2. Create fact
            Counter counter = new Counter();
            counter.setValue(value);

            // 3. Insert fact into working memory
            session.insert(counter);

            // 4. Fire all matching rules
            session.fireAllRules();

            // 5. Build response
            return new EvaluationResponse(
                counter.getValue(),
                counter.getAlert(),
                generateMessage(counter.getAlert()),
                System.currentTimeMillis()
            );
        } finally {
            // 6. Clean up session
            session.dispose();
        }
    }

    private String generateMessage(String alert) {
        return switch (alert) {
            case "NORMAL" -> "Counter value is within normal range";
            case "WARNING" -> "Counter value is elevated";
            case "CRITICAL" -> "Counter value is critical";
            case "RESET" -> "Counter has been reset";
            case "INVALID" -> "Counter value is invalid (negative)";
            default -> "Unknown state";
        };
    }
}
```

### `src/main/java/com/reactive/drools/controller/RuleController.java`
REST API controller:

```java
@RestController
@RequestMapping("/api")
public class RuleController {

    private final RuleService ruleService;

    @PostMapping("/evaluate")
    public ResponseEntity<EvaluationResponse> evaluate(
            @RequestBody EvaluationRequest request) {
        EvaluationResponse response = ruleService.evaluate(request.getValue());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/evaluate/{value}")
    public ResponseEntity<EvaluationResponse> evaluateGet(
            @PathVariable int value) {
        EvaluationResponse response = ruleService.evaluate(value);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/rules")
    public ResponseEntity<List<String>> getRules() {
        // Returns list of rule names
        return ResponseEntity.ok(ruleService.getRuleNames());
    }
}
```

### `src/main/java/com/reactive/drools/model/Counter.java`
Fact model used in rules:

```java
public class Counter {
    private int value;
    private String alert;
    private String message;

    // Getters and setters
    // Used by rules for pattern matching and modification
}
```

## REST API

### `POST /api/evaluate`
Evaluate rules for a counter value.

**Request:**
```json
{
  "value": 50
}
```

**Response:**
```json
{
  "value": 50,
  "alert": "WARNING",
  "message": "Counter value is elevated",
  "timestamp": 1702000000000
}
```

### `GET /api/evaluate/{value}`
Alternative GET endpoint for testing.

**Example:**
```bash
curl http://localhost:8180/api/evaluate/150
```

**Response:**
```json
{
  "value": 150,
  "alert": "CRITICAL",
  "message": "Counter value is critical",
  "timestamp": 1702000000000
}
```

### `GET /api/rules`
List all available rules.

**Response:**
```json
[
  "Counter Normal",
  "Counter Warning",
  "Counter Critical",
  "Counter Reset",
  "Counter Negative"
]
```

### `GET /health`
Health check endpoint.

**Response:**
```json
{
  "status": "UP"
}
```

## Drools Concepts

### Facts
Objects inserted into working memory:
```java
Counter counter = new Counter();
session.insert(counter);
```

### Rules
Condition-action pairs:
```drools
rule "Rule Name"
    when
        // Conditions (LHS - Left Hand Side)
        $counter : Counter(value > 100)
    then
        // Actions (RHS - Right Hand Side)
        modify($counter) { setAlert("CRITICAL") }
end
```

### Pattern Matching
Rules match against facts:
```drools
Counter(value >= 1, value <= 10)  // Matches if 1 <= value <= 10
Counter(value > 100)              // Matches if value > 100
```

### Salience (Priority)
Higher salience = evaluated first:
```drools
rule "High Priority"
    salience 100  // Checked before lower salience rules
```

### Modify
Updates fact and re-triggers matching:
```drools
modify($counter) { setAlert("WARNING") }
```

## Rule Evaluation Process

```
1. Insert fact
   session.insert(counter)
        │
        ▼
2. Match rules
   Which rules match the fact's current state?
        │
        ▼
3. Conflict resolution
   Order by salience (highest first)
        │
        ▼
4. Fire rules
   Execute actions of matching rules
        │
        ▼
5. Re-evaluate (if fact modified)
   Modified facts trigger re-matching
        │
        ▼
6. Extract results
   Read modified fact properties
```

## Build Process

### Maven Build
```bash
mvn clean package -DskipTests
# Output: target/drools-service-1.0.0.jar
```

### Dependencies (pom.xml)
```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Drools -->
    <dependency>
        <groupId>org.drools</groupId>
        <artifactId>drools-core</artifactId>
        <version>8.44.0.Final</version>
    </dependency>
    <dependency>
        <groupId>org.drools</groupId>
        <artifactId>drools-compiler</artifactId>
        <version>8.44.0.Final</version>
    </dependency>
    <dependency>
        <groupId>org.drools</groupId>
        <artifactId>drools-mvel</artifactId>
        <version>8.44.0.Final</version>
    </dependency>
</dependencies>
```

## Docker Configuration

### Dockerfile
Multi-stage build:
1. **deps**: Download Maven dependencies
2. **build**: Compile and package
3. **runtime**: Eclipse Temurin JRE 17

### Health Check
```dockerfile
HEALTHCHECK --start-period=30s \
    CMD curl -f http://localhost:8080/health || exit 1
```

## Modifying Rules

### 1. Edit Rule File
```bash
vim drools/src/main/resources/rules/counter-rules.drl
```

### 2. Rebuild Container
```bash
./cli.sh build drools
./cli.sh restart drools
```

### 3. Or Use Hot Reload (Dev Mode)
With mounted volume in docker-compose.dev.yml:
```bash
./cli.sh compile drools
```

## Rule Examples

### Adding a New Alert Level
```drools
rule "Counter Extreme"
    salience 6
    when
        $counter : Counter(value > 1000)
    then
        modify($counter) { setAlert("EXTREME") }
end
```

### Time-Based Rule
```drools
rule "Off Hours Warning"
    when
        $counter : Counter(value > 50)
        eval(isOffHours())
    then
        modify($counter) { setAlert("OFF_HOURS_WARNING") }
end
```

### Multiple Conditions
```drools
rule "Complex Condition"
    when
        $counter : Counter(
            value > 50,
            value < 100,
            alert != "CRITICAL"
        )
    then
        // Action
end
```

## Common Issues

### Rules Not Firing
**Symptom**: Always returns default/null alert
**Cause**: DRL syntax error or fact mismatch
**Solution**: Check logs for compilation errors; verify fact class matches

### Wrong Rule Firing
**Symptom**: Unexpected alert level
**Cause**: Salience order incorrect
**Solution**: Review salience values; higher = checked first

### Slow Evaluation
**Symptom**: High latency on /api/evaluate
**Cause**: Complex rules or many facts
**Solution**: Optimize rules; consider caching KieBase

### Container Won't Start
**Symptom**: Drools container exits immediately
**Cause**: DRL compilation error
**Solution**: Check container logs; validate DRL syntax

## Testing Rules

### Unit Test
```java
@Test
void testWarningAlert() {
    EvaluationResponse response = ruleService.evaluate(50);
    assertEquals("WARNING", response.getAlert());
}
```

### Manual Test
```bash
# Test each alert level
curl http://localhost:8180/api/evaluate/0    # RESET
curl http://localhost:8180/api/evaluate/5    # NORMAL
curl http://localhost:8180/api/evaluate/50   # WARNING
curl http://localhost:8180/api/evaluate/150  # CRITICAL
curl http://localhost:8180/api/evaluate/-1   # INVALID
```
