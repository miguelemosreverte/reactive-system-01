import { Server as HttpServer } from 'http';
import { WebSocketServer as WSServer, WebSocket } from 'ws';
import { v4 as uuidv4 } from 'uuid';

interface WebSocketClient {
    ws: WebSocket;
    sessionId: string;
}

export class WebSocketServer {
    private wss: WSServer;
    private clients: Map<string, WebSocketClient> = new Map();
    private messageHandler?: (sessionId: string, message: any) => void;

    constructor(server: HttpServer) {
        this.wss = new WSServer({ server, path: '/ws' });
        this.setupHandlers();
    }

    private setupHandlers(): void {
        this.wss.on('connection', (ws: WebSocket) => {
            const clientId = uuidv4();
            const sessionId = uuidv4();

            console.log(`WebSocket client connected: ${clientId} (session: ${sessionId})`);

            this.clients.set(clientId, { ws, sessionId });

            // Send welcome message with session ID
            ws.send(JSON.stringify({
                type: 'connected',
                clientId,
                sessionId,
                timestamp: Date.now()
            }));

            ws.on('message', (data: Buffer) => {
                try {
                    const message = JSON.parse(data.toString());

                    // Allow clients to set their session ID
                    if (message.type === 'set-session') {
                        const client = this.clients.get(clientId);
                        if (client && message.sessionId) {
                            client.sessionId = message.sessionId;
                            console.log(`Client ${clientId} set session to ${message.sessionId}`);
                        }
                        return;
                    }

                    // Forward to message handler
                    if (this.messageHandler) {
                        const client = this.clients.get(clientId);
                        if (client) {
                            this.messageHandler(client.sessionId, message);
                        }
                    }
                } catch (error) {
                    console.error('Failed to parse WebSocket message:', error);
                }
            });

            ws.on('close', () => {
                console.log(`WebSocket client disconnected: ${clientId}`);
                this.clients.delete(clientId);
            });

            ws.on('error', (error) => {
                console.error(`WebSocket error for client ${clientId}:`, error);
                this.clients.delete(clientId);
            });
        });
    }

    onMessage(handler: (sessionId: string, message: any) => void): void {
        this.messageHandler = handler;
    }

    broadcast(sessionId: string, message: any): void {
        const data = JSON.stringify(message);

        for (const [, client] of this.clients) {
            if (client.sessionId === sessionId && client.ws.readyState === WebSocket.OPEN) {
                client.ws.send(data);
            }
        }
    }

    broadcastAll(message: any): void {
        const data = JSON.stringify(message);

        for (const [, client] of this.clients) {
            if (client.ws.readyState === WebSocket.OPEN) {
                client.ws.send(data);
            }
        }
    }

    sendTo(sessionId: string, message: any): void {
        this.broadcast(sessionId, message);
    }

    getConnectedClients(): number {
        return this.clients.size;
    }
}
