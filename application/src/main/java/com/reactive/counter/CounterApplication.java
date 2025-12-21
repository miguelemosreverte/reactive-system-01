package com.reactive.counter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Counter Application entry point.
 *
 * This is a Spring Boot application that uses the reactive platform
 * for infrastructure (Kafka, tracing) and focuses on domain logic.
 */
@SpringBootApplication
public class CounterApplication {

    public static void main(String[] args) {
        SpringApplication.run(CounterApplication.class, args);
    }
}
