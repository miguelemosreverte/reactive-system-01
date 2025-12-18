import { Kafka, Producer, Consumer, EachMessagePayload } from 'kafkajs';

export interface CounterEvent {
    sessionId: string;
    action: string;
    value: number;
    timestamp: number;
}

export interface CounterResult {
    sessionId: string;
    currentValue: number;
    alert: string;
    message: string;
    timestamp: number;
}

export class KafkaClient {
    private kafka: Kafka;
    private producer: Producer;
    private consumer: Consumer;
    private isConnected: boolean = false;

    constructor(brokers: string[]) {
        this.kafka = new Kafka({
            clientId: 'reactive-gateway',
            brokers,
            retry: {
                initialRetryTime: 1000,
                retries: 10
            }
        });

        this.producer = this.kafka.producer();
        this.consumer = this.kafka.consumer({ groupId: 'gateway-group' });
    }

    async connect(): Promise<void> {
        console.log('Connecting to Kafka...');

        // Retry connection with backoff
        let retries = 0;
        const maxRetries = 30;

        while (retries < maxRetries) {
            try {
                await this.producer.connect();
                await this.consumer.connect();
                this.isConnected = true;
                console.log('Connected to Kafka');
                return;
            } catch (error) {
                retries++;
                console.log(`Kafka connection attempt ${retries}/${maxRetries} failed, retrying in 2s...`);
                await new Promise(resolve => setTimeout(resolve, 2000));
            }
        }

        throw new Error('Failed to connect to Kafka after maximum retries');
    }

    async disconnect(): Promise<void> {
        await this.producer.disconnect();
        await this.consumer.disconnect();
        this.isConnected = false;
    }

    async publishEvent(event: CounterEvent): Promise<void> {
        if (!this.isConnected) {
            throw new Error('Kafka client not connected');
        }

        await this.producer.send({
            topic: 'counter-events',
            messages: [
                {
                    key: event.sessionId,
                    value: JSON.stringify(event)
                }
            ]
        });

        console.log('Published event:', event);
    }

    async subscribeToResults(callback: (result: CounterResult) => void): Promise<void> {
        await this.consumer.subscribe({ topic: 'counter-results', fromBeginning: false });

        await this.consumer.run({
            eachMessage: async ({ message }: EachMessagePayload) => {
                if (message.value) {
                    try {
                        const result: CounterResult = JSON.parse(message.value.toString());
                        callback(result);
                    } catch (error) {
                        console.error('Failed to parse result:', error);
                    }
                }
            }
        });

        console.log('Subscribed to counter-results topic');
    }
}
