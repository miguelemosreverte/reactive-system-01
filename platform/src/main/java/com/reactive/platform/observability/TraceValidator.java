package com.reactive.platform.observability;

import com.reactive.platform.benchmark.BenchmarkTypes.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates distributed trace completeness across all system components.
 *
 * For a full E2E trace, we expect spans from:
 * - counter-application (Gateway)
 * - flink-taskmanager (Flink)
 * - drools (Rules engine)
 *
 * This validator checks that ALL expected components are present in
 * the SAME trace, not just that each component has spans somewhere.
 */
public class TraceValidator {

    // Expected services for full E2E trace
    public static final List<String> E2E_EXPECTED_SERVICES = List.of(
            "counter-application",  // Gateway
            "flink-taskmanager",    // Flink
            "drools"                // Rules engine
    );

    // Component-specific expected services
    public static final Map<String, List<String>> COMPONENT_EXPECTED_SERVICES = Map.of(
            "http", List.of("counter-application"),
            "kafka", List.of("counter-application"),
            "gateway", List.of("counter-application"),
            "flink", List.of("counter-application", "flink-taskmanager"),
            "drools", List.of("drools"),
            "full", E2E_EXPECTED_SERVICES
    );

    // Expected operations for each service (key operations, not exhaustive)
    public static final Map<String, List<String>> KEY_OPERATIONS = Map.of(
            "counter-application", List.of("POST", "counter", "kafka"),
            "flink-taskmanager", List.of("process", "counter", "kafka"),
            "drools", List.of("evaluate", "RuleController")
    );

    /**
     * Result of trace validation.
     */
    public record ValidationResult(
            boolean isComplete,
            boolean isUnified,
            String traceId,
            int spanCount,
            List<String> presentServices,
            List<String> missingServices,
            List<String> operations,
            Map<String, Integer> spanCountByService,
            List<String> issues,
            long traceStartTime,
            long traceDurationMs
    ) {
        public static ValidationResult failure(String reason) {
            return new ValidationResult(
                    false, false, "", 0,
                    List.of(), List.of(),
                    List.of(), Map.of(),
                    List.of(reason), 0, 0
            );
        }

        /**
         * Get a summary string for logging/display.
         */
        public String summary() {
            if (!isComplete) {
                return String.format("INCOMPLETE: Missing services: %s", missingServices);
            }
            return String.format("COMPLETE: %d spans across %d services (%s)",
                    spanCount, presentServices.size(), String.join(", ", presentServices));
        }

        /**
         * Convert to JSON-compatible map.
         */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("isComplete", isComplete);
            map.put("isUnified", isUnified);
            map.put("traceId", traceId);
            map.put("spanCount", spanCount);
            map.put("presentServices", presentServices);
            map.put("missingServices", missingServices);
            map.put("spanCountByService", spanCountByService);
            map.put("operations", operations);
            map.put("issues", issues);
            map.put("traceStartTime", traceStartTime);
            map.put("traceDurationMs", traceDurationMs);
            return map;
        }
    }

    /**
     * Validate a trace for full E2E completeness.
     */
    public static ValidationResult validateE2E(Trace trace) {
        return validate(trace, E2E_EXPECTED_SERVICES);
    }

    /**
     * Validate a trace for a specific component's expected services.
     */
    public static ValidationResult validateForComponent(Trace trace, String componentId) {
        List<String> expected = COMPONENT_EXPECTED_SERVICES.getOrDefault(
                componentId.toLowerCase(),
                E2E_EXPECTED_SERVICES
        );
        return validate(trace, expected);
    }

    /**
     * Validate a trace against expected services.
     */
    public static ValidationResult validate(Trace trace, List<String> expectedServices) {
        if (trace == null) {
            return ValidationResult.failure("No trace data available");
        }

        if (trace.spans() == null || trace.spans().isEmpty()) {
            return ValidationResult.failure("Trace has no spans");
        }

        List<String> issues = new ArrayList<>();

        // Extract services from processes using streams
        Map<String, Integer> spanCountByService = trace.spans().stream()
            .map(span -> trace.processes().get(span.processId()))
            .filter(java.util.Objects::nonNull)
            .map(Service::serviceName)
            .collect(Collectors.groupingBy(s -> s, Collectors.collectingAndThen(
                Collectors.counting(), Long::intValue)));

        List<String> presentServices = spanCountByService.keySet().stream()
            .sorted()
            .collect(Collectors.toList());
        Set<String> presentServicesSet = spanCountByService.keySet();

        // Check for missing services
        List<String> missingServices = expectedServices.stream()
                .filter(svc -> !presentServicesSet.contains(svc))
                .collect(Collectors.toList());

        boolean isComplete = missingServices.isEmpty();
        boolean isUnified = true; // All spans are in the same trace by definition

        if (!isComplete) {
            issues.add("Missing services: " + String.join(", ", missingServices));
        }

        // Extract operations
        List<String> operations = trace.spans().stream()
                .map(span -> {
                    String processId = span.processId();
                    Service process = trace.processes().get(processId);
                    String svc = process != null ? process.serviceName() : "unknown";
                    return svc + ":" + span.operationName();
                })
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Validate key operations are present
        presentServicesSet.stream()
            .filter(service -> KEY_OPERATIONS.containsKey(service))
            .filter(service -> !hasKeyOperation(service, operations))
            .forEach(service -> issues.add(String.format(
                "Service '%s' missing expected operations (expected one of: %s)",
                service, KEY_OPERATIONS.get(service))));

        // Calculate trace timing
        long traceStartTime = trace.spans().stream()
                .mapToLong(Span::startTime)
                .min()
                .orElse(0);

        long traceEndTime = trace.spans().stream()
                .mapToLong(s -> s.startTime() + s.duration())
                .max()
                .orElse(0);

        long traceDurationMs = (traceEndTime - traceStartTime) / 1000; // Convert from micros

        return new ValidationResult(
                isComplete,
                isUnified,
                trace.traceId(),
                trace.spans().size(),
                presentServices,
                missingServices,
                operations,
                spanCountByService,
                issues,
                traceStartTime,
                traceDurationMs
        );
    }

    /**
     * Check if service has at least one key operation in the operations list.
     */
    private static boolean hasKeyOperation(String service, List<String> operations) {
        List<String> keyOps = KEY_OPERATIONS.get(service);
        if (keyOps == null) return true;

        String prefix = service + ":";
        return keyOps.stream().anyMatch(op ->
            operations.stream().anyMatch(fullOp ->
                fullOp.startsWith(prefix) && fullOp.toLowerCase().contains(op.toLowerCase())));
    }

    /**
     * Check if a trace ID looks valid (32 hex characters).
     */
    public static boolean isValidTraceId(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return false;
        }
        return traceId.matches("[a-fA-F0-9]{32}");
    }

    /**
     * Validate multiple traces and return summary statistics.
     */
    public static TraceSummary validateMultiple(List<Trace> traces, List<String> expectedServices) {
        List<ValidationResult> results = traces.stream()
            .map(trace -> validate(trace, expectedServices))
            .collect(Collectors.toList());

        Map<Boolean, List<ValidationResult>> partitioned = results.stream()
            .collect(Collectors.partitioningBy(ValidationResult::isComplete));

        int complete = partitioned.get(true).size();
        int incomplete = partitioned.get(false).size();
        int total = traces.size();

        Map<String, Integer> missingServiceCounts = partitioned.get(false).stream()
            .flatMap(r -> r.missingServices().stream())
            .collect(Collectors.groupingBy(s -> s, Collectors.collectingAndThen(
                Collectors.counting(), Long::intValue)));

        List<String> incompleteTraceIds = partitioned.get(false).stream()
            .limit(10)
            .map(ValidationResult::traceId)
            .collect(Collectors.toList());

        return new TraceSummary(
                total, complete, incomplete,
                total > 0 ? (complete * 100.0 / total) : 0.0,
                missingServiceCounts,
                incompleteTraceIds
        );
    }

    /**
     * Summary of multiple trace validations.
     */
    public record TraceSummary(
            int totalTraces,
            int completeTraces,
            int incompleteTraces,
            double completenessRate,
            Map<String, Integer> missingServiceCounts,
            List<String> sampleIncompleteTraceIds
    ) {
        public boolean isFullyComplete() {
            return incompleteTraces == 0 && totalTraces > 0;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalTraces", totalTraces);
            map.put("completeTraces", completeTraces);
            map.put("incompleteTraces", incompleteTraces);
            map.put("completenessRate", String.format("%.1f%%", completenessRate));
            map.put("isFullyComplete", isFullyComplete());
            map.put("missingServiceCounts", missingServiceCounts);
            map.put("sampleIncompleteTraceIds", sampleIncompleteTraceIds);
            return map;
        }
    }
}
