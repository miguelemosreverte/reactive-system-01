package com.reactive.platform.http;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Spring Boot WebFlux HTTP server for benchmark comparison.
 *
 * Uses Spring's reactive stack (Netty under the hood).
 * This represents what most enterprise applications use.
 */
@SpringBootApplication
@RestController
public class SpringBootHttpServer {

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> health() {
        return Mono.just("{\"status\":\"UP\"}");
    }

    @PostMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> events(@RequestBody(required = false) String body) {
        return Mono.just("{\"ok\":true}");
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9999;

        System.setProperty("server.port", String.valueOf(port));
        System.setProperty("logging.level.root", "WARN");
        System.setProperty("spring.main.banner-mode", "off");

        ConfigurableApplicationContext ctx = SpringApplication.run(SpringBootHttpServer.class, args);

        System.out.printf("[SpringBootHttpServer] Started on port %d%n", port);
        System.out.println("Server running. Press Ctrl+C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[SpringBootHttpServer] Stopped");
            ctx.close();
        }));
    }
}
