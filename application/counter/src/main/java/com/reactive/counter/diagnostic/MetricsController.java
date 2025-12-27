package com.reactive.counter.diagnostic;

import com.reactive.diagnostic.DiagnosticCollector;
import com.reactive.diagnostic.DiagnosticSnapshot;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST endpoint for diagnostic data.
 * Exposes the current diagnostic snapshot in JSON format.
 */
@RestController("metricsController")
@RequestMapping("/api/diagnostics")
public class MetricsController {

    private final DiagnosticCollector diagnosticCollector;

    public MetricsController(DiagnosticCollector diagnosticCollector) {
        this.diagnosticCollector = diagnosticCollector;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<DiagnosticSnapshot> getDiagnostics() {
        return Mono.fromSupplier(diagnosticCollector::collect);
    }
}
