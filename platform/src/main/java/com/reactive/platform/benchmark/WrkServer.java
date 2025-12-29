package com.reactive.platform.benchmark;

import com.reactive.platform.http.RocketHttpServer;

/**
 * Simple server for wrk benchmarking.
 */
public final class WrkServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;
        int reactors = args.length > 1 ? Integer.parseInt(args[1]) : Runtime.getRuntime().availableProcessors();

        System.out.printf("Starting RocketHttpServer on port %d with %d reactors...%n", port, reactors);

        RocketHttpServer server = RocketHttpServer.create()
            .reactors(reactors)
            .onBody(buf -> {});

        try (RocketHttpServer.Handle handle = server.start(port)) {
            System.out.println("Server running. Press Ctrl+C to stop.");
            handle.awaitTermination();
        }
    }
}
