package com.reactive.platform.http.server;

import java.util.Collections;
import java.util.HashMap;
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
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", String.valueOf(contentLength));
        return Collections.unmodifiableMap(headers);
    }
}
