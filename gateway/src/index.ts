import express from 'express';
import { createServer } from 'http';
import { WebSocketServer } from './websocket';
import { KafkaClient } from './kafka';

const PORT = parseInt(process.env.PORT || '8080', 10);
const KAFKA_BROKERS = (process.env.KAFKA_BROKERS || 'kafka:29092').split(',');

// State to track latest counter value per session
const counterState: Map<string, { value: number; alert: string; message: string }> = new Map();

async function main() {
    const app = express();
    app.use(express.json());

    // Health endpoint
    app.get('/health', (req, res) => {
        res.json({
            status: 'UP',
            service: 'gateway',
            timestamp: Date.now()
        });
    });

    // REST API for counter operations
    app.post('/api/counter', async (req, res) => {
        const { action, value, sessionId = 'default' } = req.body;

        try {
            await kafkaClient.publishEvent({
                sessionId,
                action: action || 'set',
                value: value || 0,
                timestamp: Date.now()
            });

            res.json({ success: true, message: 'Event published' });
        } catch (error) {
            console.error('Failed to publish event:', error);
            res.status(500).json({ success: false, error: 'Failed to publish event' });
        }
    });

    // Get current counter status
    app.get('/api/counter/status', (req, res) => {
        const sessionId = (req.query.sessionId as string) || 'default';
        const state = counterState.get(sessionId) || { value: 0, alert: 'NONE', message: 'No events processed yet' };
        res.json(state);
    });

    // Create HTTP server
    const server = createServer(app);

    // Initialize Kafka client
    const kafkaClient = new KafkaClient(KAFKA_BROKERS);

    // Initialize WebSocket server
    const wsServer = new WebSocketServer(server);

    // Connect to Kafka
    await kafkaClient.connect();

    // Subscribe to counter results
    await kafkaClient.subscribeToResults((result) => {
        console.log('Received result:', result);

        // Update state
        counterState.set(result.sessionId, {
            value: result.currentValue,
            alert: result.alert,
            message: result.message
        });

        // Broadcast to all WebSocket clients for this session
        wsServer.broadcast(result.sessionId, {
            type: 'counter-update',
            data: result
        });
    });

    // Handle WebSocket messages
    wsServer.onMessage((sessionId, message) => {
        console.log(`WebSocket message from ${sessionId}:`, message);

        if (message.type === 'counter-action') {
            kafkaClient.publishEvent({
                sessionId,
                action: message.action || 'increment',
                value: message.value || 1,
                timestamp: Date.now()
            });
        } else if (message.type === 'ping') {
            wsServer.sendTo(sessionId, { type: 'pong', timestamp: Date.now() });
        }
    });

    // Start server
    server.listen(PORT, () => {
        console.log(`Gateway server listening on port ${PORT}`);
        console.log(`Health check: http://localhost:${PORT}/health`);
        console.log(`WebSocket: ws://localhost:${PORT}/ws`);
    });

    // Graceful shutdown
    process.on('SIGTERM', async () => {
        console.log('Shutting down...');
        await kafkaClient.disconnect();
        server.close();
        process.exit(0);
    });
}

main().catch((error) => {
    console.error('Failed to start gateway:', error);
    process.exit(1);
});
