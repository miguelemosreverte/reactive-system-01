package com.reactive.platform.http.server;

import java.util.Map;

/**
 * Simple immutable Response implementation.
 */
public record SimpleResponse(
    int status,
    Map<String, String> headers,
    byte[] body
) implements HttpServerSpec.Response {

    public SimpleResponse(int status, byte[] body) {
        this(status, defaultHeaders(body.length), body);
    }

    private static Map<String, String> defaultHeaders(int contentLength) {
        return Map.of(
            "Content-Type", "application/json",
            "Content-Length", String.valueOf(contentLength)
        );
    }
}
