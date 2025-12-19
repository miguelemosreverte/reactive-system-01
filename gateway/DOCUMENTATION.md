# Gateway Component Documentation

## Overview

The Gateway is a Node.js service that acts as a bridge between the web frontend and the Kafka-based backend. It translates WebSocket messages to Kafka events and vice versa.

## Technology Stack

| Technology | Purpose |
|------------|---------|
| Node.js 20 | Runtime environment |
| TypeScript | Type-safe JavaScript |
| Express | HTTP server framework |
| ws | WebSocket server library |
| kafkajs | Kafka client library |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Gateway Service                          │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                      index.ts                            │   │
│  │  - Express HTTP server (port 8080)                       │   │
│  │  - REST API endpoints                                    │   │
│  │  - Coordinates WebSocket and Kafka                       │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│              ┌───────────────┴───────────────┐                  │
│              ▼                               ▼                  │
│  ┌─────────────────────────┐   ┌─────────────────────────────┐ │
│  │     websocket.ts        │   │         kafka.ts            │ │
│  │  - WebSocket server     │   │  - Kafka producer           │ │
│  │  - Client management    │   │  - Kafka consumer           │ │
│  │  - Session tracking     │   │  - Connection management    │ │
│  └─────────────────────────┘   └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
         │                                      │
         │ WebSocket                            │ Kafka
         ▼                                      ▼
    ┌─────────┐                          ┌─────────────┐
    │   UI    │                          │    Kafka    │
    └─────────┘                          │   Broker    │
                                         └─────────────┘
```

## Key Files

### `src/index.ts`
Main entry point that:
- Creates Express HTTP server
- Initializes WebSocket server
- Connects to Kafka
- Sets up message routing
- Defines REST API endpoints

**Flow:**
1. WebSocket message received
2. Parse and validate message
3. Add session context
4. Publish to Kafka `counter-events`
5. (Later) Kafka `counter-results` received
6. Find matching WebSocket client
7. Send update to client

### `src/websocket.ts`
WebSocket server wrapper class:

**Client Management:**
```typescript
interface WebSocketClient {
    ws: WebSocket;
    sessionId: string;
}
private clients: Map<string, WebSocketClient>
```

**Key Methods:**
- `constructor(server)`: Attach to HTTP server at `/ws` path
- `onMessage(handler)`: Register message callback
- `broadcast(sessionId, message)`: Send to all clients with session
- `broadcastAll(message)`: Send to all connected clients
- `getConnectedClients()`: Return client count

**Connection Lifecycle:**
1. Client connects → Generate clientId and sessionId
2. Send `connected` message with IDs
3. Register message handler
4. On message → Forward to handler with sessionId
5. On close → Remove from clients map
6. On error → Log and remove client

### `src/kafka.ts`
Kafka client wrapper class:

**Connection:**
- Broker address from `KAFKA_BROKERS` env var
- Client ID: `reactive-gateway`
- Consumer group: `gateway-group`

**Key Methods:**
- `connect()`: Initialize producer and consumer
- `publish(topic, message)`: Send event with partition key
- `subscribe(topic, handler)`: Consume messages with callback
- `disconnect()`: Clean shutdown

**Retry Logic:**
- Max 30 attempts on initial connection
- 2 second delay between retries
- Exponential backoff for transient failures

## REST API

### `POST /api/counter`
Send a counter action (alternative to WebSocket).

**Request:**
```json
{
  "action": "increment",
  "value": 1,
  "sessionId": "optional-session-id"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Action sent",
  "sessionId": "generated-if-not-provided"
}
```

### `GET /api/counter/status`
Get gateway status.

**Response:**
```json
{
  "connectedClients": 5,
  "kafkaConnected": true
}
```

### `GET /health`
Health check endpoint.

**Response:**
```json
{
  "status": "ok",
  "timestamp": 1702000000000
}
```

## Message Flow

### Inbound (UI → Kafka)

```
UI sends WebSocket message
         │
         ▼
┌─────────────────────────────┐
│  WebSocket Server receives  │
│  Parse JSON message         │
└─────────────────────────────┘
         │
         ▼
┌─────────────────────────────┐
│  Message handler (index.ts) │
│  - Validate message type    │
│  - Extract action/value     │
│  - Add timestamp            │
└─────────────────────────────┘
         │
         ▼
┌─────────────────────────────┐
│  Kafka publish              │
│  Topic: counter-events      │
│  Key: sessionId             │
└─────────────────────────────┘
```

### Outbound (Kafka → UI)

```
Kafka consumer receives message
         │
         ▼
┌─────────────────────────────┐
│  Message handler (index.ts) │
│  - Parse result             │
│  - Extract sessionId        │
└─────────────────────────────┘
         │
         ▼
┌─────────────────────────────┐
│  WebSocket broadcast        │
│  - Find clients by session  │
│  - Send counter-update msg  │
└─────────────────────────────┘
         │
         ▼
    UI receives update
```

## Session Management

Sessions link WebSocket clients to their Kafka events:

1. **On Connect**: Generate UUID for sessionId
2. **On Message**: Include sessionId in Kafka event
3. **On Result**: Route result to correct WebSocket by sessionId
4. **On Disconnect**: Remove client (session can be resumed)

**Session ID Flow:**
```
UI connects → Gateway generates sessionId
UI sends action → Gateway adds sessionId to Kafka event
Flink processes → Keeps sessionId in result
Gateway receives result → Routes to WebSocket by sessionId
```

## State Management

The Gateway maintains minimal state:

```typescript
// Connected WebSocket clients
clients: Map<string, WebSocketClient>

// Session state (optional caching)
counterState: Map<string, { value: number, alert: string }>
```

**Note**: The authoritative state is in Flink. Gateway state is for quick reference only.

## Error Handling

### Kafka Connection Failure
- Retry with exponential backoff
- Log connection attempts
- Health endpoint reflects status

### WebSocket Errors
- Log error details
- Remove client from map
- Connection closed automatically

### Message Parse Errors
- Log malformed message
- Ignore and continue
- Don't crash on bad input

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8080 | HTTP server port |
| `KAFKA_BROKERS` | kafka:29092 | Kafka broker addresses |
| `NODE_ENV` | development | Environment mode |

## Docker Configuration

**Dockerfile stages:**
1. **deps**: Install production dependencies
2. **build**: Compile TypeScript
3. **runtime**: Node.js Alpine with compiled code

**Health Check:**
```dockerfile
HEALTHCHECK CMD curl -f http://localhost:8080/health
```

## Scaling Considerations

### Multiple Gateway Instances
- Each instance joins same Kafka consumer group
- Kafka partitions distributed across instances
- Sticky sessions needed for WebSocket routing

### Shared State
For multiple instances, consider:
- Redis for session → instance mapping
- Redis pub/sub for cross-instance broadcasts
- Sticky load balancing

## Common Issues

### Kafka Connection Refused
**Symptom**: Gateway fails to start
**Cause**: Kafka not ready
**Solution**: Gateway retries automatically; check Kafka health

### WebSocket 502 from Nginx
**Symptom**: UI can't connect
**Cause**: Gateway not ready or wrong proxy config
**Solution**: Check Gateway logs and Nginx config

### Messages Not Reaching UI
**Symptom**: Actions work but no updates
**Cause**: Consumer group offset issue or sessionId mismatch
**Solution**: Check Kafka consumer lag and session routing
