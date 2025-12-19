# UI Component Documentation

## Overview

The UI is a React single-page application that provides the user interface for the reactive counter system. It demonstrates real-time, bidirectional communication using WebSockets.

## Technology Stack

| Technology | Purpose |
|------------|---------|
| React 18 | UI framework with hooks |
| TypeScript | Type-safe JavaScript |
| Vite | Fast build tool with HMR |
| Nginx | Production static file server |

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        React Application                        │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                       App.tsx                            │   │
│  │  - Main application component                            │   │
│  │  - Manages counter state (value, alert, message)         │   │
│  │  - Coordinates child components                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│              ┌───────────────┼───────────────┐                  │
│              ▼               ▼               ▼                  │
│  ┌─────────────────┐ ┌─────────────┐ ┌─────────────────────┐   │
│  │   Counter.tsx   │ │ConnectionSt.│ │   useWebSocket.ts   │   │
│  │  - Display      │ │  - Status   │ │  - WS connection    │   │
│  │  - Buttons      │ │  - Session  │ │  - Auto-reconnect   │   │
│  └─────────────────┘ └─────────────┘ └─────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ WebSocket (ws://localhost:3000/ws)
                              ▼
                    ┌─────────────────┐
                    │  Nginx Proxy    │
                    │  → Gateway:8080 │
                    └─────────────────┘
```

## Key Files

### `src/App.tsx`
Main application component that:
- Initializes WebSocket connection via `useWebSocket` hook
- Manages application state (counterValue, alert, message)
- Handles user actions (increment, decrement, reset, set)
- Renders child components with props

### `src/hooks/useWebSocket.ts`
Custom React hook for WebSocket management:
- Establishes connection to `/ws` endpoint
- Handles automatic reconnection on disconnect (3s delay)
- Parses incoming JSON messages
- Provides `sendMessage` function for outgoing messages
- Returns connection status and session ID

**Key Features:**
- Uses refs to store callbacks (prevents infinite reconnection)
- Cleans up on component unmount
- Handles connection errors gracefully

### `src/components/Counter.tsx`
Display component showing:
- Current counter value (large number)
- Alert level with color coding
- Message from Drools rules
- Action buttons (+, -, Reset, Set Value)

### `src/components/ConnectionStatus.tsx`
Status indicator showing:
- Connection state (Connected/Disconnected)
- Session ID (truncated for display)
- Visual indicator (green/red dot)

## WebSocket Communication

### Outgoing Messages

**Counter Action:**
```typescript
{
  type: 'counter-action',
  action: 'increment' | 'decrement' | 'set',
  value: number
}
```

**Ping (keep-alive):**
```typescript
{
  type: 'ping'
}
```

### Incoming Messages

**Connected (on connection):**
```typescript
{
  type: 'connected',
  sessionId: string,
  clientId: string,
  timestamp: number
}
```

**Counter Update:**
```typescript
{
  type: 'counter-update',
  data: {
    currentValue: number,
    alert: 'NORMAL' | 'WARNING' | 'CRITICAL' | 'RESET' | 'INVALID',
    message: string
  }
}
```

## Styling

The UI uses inline styles for simplicity:

| Alert Level | Color |
|-------------|-------|
| NORMAL | Green (#22c55e) |
| WARNING | Yellow (#eab308) |
| CRITICAL | Red (#ef4444) |
| RESET | Gray (#6b7280) |
| INVALID | Red (#ef4444) |

## Build Process

### Development
```bash
npm run dev
# Starts Vite dev server with HMR on port 5173
```

### Production
```bash
npm run build
# Outputs to dist/ directory
```

### Docker Build
The Dockerfile uses multi-stage build:
1. **deps**: Install dependencies (cached)
2. **build**: Compile TypeScript and bundle
3. **runtime**: Nginx serves static files

## Nginx Configuration

The `nginx.conf` handles:
- Static file serving from `/usr/share/nginx/html`
- SPA routing (all routes → `index.html`)
- API proxy: `/api/*` → `gateway:8080`
- WebSocket proxy: `/ws` → `gateway:8080`
- Gzip compression
- Static asset caching (1 year)

**WebSocket Proxy Headers:**
```nginx
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 86400;  # 24 hour timeout
```

## State Management

The application uses React's built-in state management:

```typescript
const [counterValue, setCounterValue] = useState(0)
const [alert, setAlert] = useState<string>('NONE')
const [message, setMessage] = useState<string>('')
```

State is updated when `counter-update` messages arrive via WebSocket.

## Error Handling

- **Connection Loss**: Auto-reconnect after 3 seconds
- **Parse Errors**: Logged to console, message ignored
- **Send Failures**: Warning logged if WebSocket not open

## Testing

Run the development server and:
1. Open browser console for WebSocket logs
2. Click buttons to trigger actions
3. Observe real-time updates
4. Test disconnect/reconnect by stopping Gateway

## Common Issues

### WebSocket Flickering
**Symptom**: Rapid connect/disconnect cycles
**Cause**: Callback dependencies causing reconnection
**Solution**: Use refs for callbacks (implemented)

### Stale State
**Symptom**: UI shows wrong value after reconnect
**Cause**: Server state not synced on reconnect
**Solution**: Server sends current state on connection

### Nginx 502 Errors
**Symptom**: WebSocket fails to connect
**Cause**: Gateway not ready
**Solution**: Wait for Gateway health check to pass
