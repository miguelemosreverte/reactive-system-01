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
                    false, false, null, 0,
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
    public static ValidationResult validateE2E(JaegerTrace trace) {
        return validate(trace, E2E_EXPECTED_SERVICES);
    }

    /**
     * Validate a trace for a specific component's expected services.
     */
    public static ValidationResult validateForComponent(JaegerTrace trace, String componentId) {
        List<String> expected = COMPONENT_EXPECTED_SERVICES.getOrDefault(
                componentId.toLowerCase(),
                E2E_EXPECTED_SERVICES
        );
        return validate(trace, expected);
    }

    /**
     * Validate a trace against expected services.
     */
    public static ValidationResult validate(JaegerTrace trace, List<String> expectedServices) {
        if (trace == null) {
            return ValidationResult.failure("No trace data available");
        }

        if (trace.spans() == null || trace.spans().isEmpty()) {
            return ValidationResult.failure("Trace has no spans");
        }

        List<String> issues = new ArrayList<>();

        // Extract services from processes
        Set<String> presentServicesSet = new HashSet<>();
        Map<String, Integer> spanCountByService = new HashMap<>();

        for (JaegerSpan span : trace.spans()) {
            String processId = span.processId();
            JaegerProcess process = trace.processes().get(processId);
            if (process != null) {
                String serviceName = process.serviceName();
                presentServicesSet.add(serviceName);
                spanCountByService.merge(serviceName, 1, Integer::sum);
            }
        }

        List<String> presentServices = new ArrayList<>(presentServicesSet);
        Collections.sort(presentServices);

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
                    JaegerProcess process = trace.processes().get(processId);
                    String svc = process != null ? process.serviceName() : "unknown";
                    return svc + ":" + span.operationName();
                })
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // Validate key operations are present
        for (String service : presentServicesSet) {
            List<String> keyOps = KEY_OPERATIONS.get(service);
            if (keyOps != null) {
                boolean hasKeyOp = false;
                for (String op : keyOps) {
                    for (String fullOp : operations) {
                        if (fullOp.startsWith(service + ":") &&
                                fullOp.toLowerCase().contains(op.toLowerCase())) {
                            hasKeyOp = true;
                            break;
                        }
                    }
                    if (hasKeyOp) break;
                }
                if (!hasKeyOp) {
                    issues.add(String.format("Service '%s' missing expected operations (expected one of: %s)",
                            service, keyOps));
                }
            }
        }

        // Calculate trace timing
        long traceStartTime = trace.spans().stream()
                .mapToLong(JaegerSpan::startTime)
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
    public static TraceSummary validateMultiple(List<JaegerTrace> traces, List<String> expectedServices) {
        int total = traces.size();
        int complete = 0;
        int incomplete = 0;
        Map<String, Integer> missingServiceCounts = new HashMap<>();
        List<String> incompleteTraceIds = new ArrayList<>();

        for (JaegerTrace trace : traces) {
            ValidationResult result = validate(trace, expectedServices);
            if (result.isComplete()) {
                complete++;
            } else {
                incomplete++;
                incompleteTraceIds.add(trace.traceId());
                for (String missing : result.missingServices()) {
                    missingServiceCounts.merge(missing, 1, Integer::sum);
                }
            }
        }

        return new TraceSummary(
                total, complete, incomplete,
                total > 0 ? (complete * 100.0 / total) : 0.0,
                missingServiceCounts,
                incompleteTraceIds.size() > 10
                        ? incompleteTraceIds.subList(0, 10)
                        : incompleteTraceIds
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
