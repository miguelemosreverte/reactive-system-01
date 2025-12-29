/**
 * DiagnosticSnapshot schema - TypeScript types matching the Go diagnostic types.
 * This is the CONTRACT - the UI should display all of this data.
 *
 * If any field is missing or empty in the actual data, the diagnostic tool
 * will generate a task list to fix data collection.
 */

// ============================================================================
// Benchmark Result Types (Current Schema)
// ============================================================================

export interface BenchmarkData {
  component: string;
  name: string;
  description: string;
  startTime: string;
  endTime: string;
  durationMs: number;
  totalOperations: number;
  successfulOperations: number;
  failedOperations: number;
  peakThroughput: number;
  avgThroughput: number;
  throughputTimeline: number[];
  latency: {
    min: number;
    max: number;
    avg: number;
    p50: number;
    p95: number;
    p99: number;
  };
  throughputStability?: number;
  cpuTimeline: number[];
  memoryTimeline: number[];
  peakCpu: number;
  peakMemory: number;
  avgCpu: number;
  avgMemory: number;
  componentTiming: {
    gatewayMs: number;
    kafkaMs: number;
    flinkMs: number;
    droolsMs: number;
  } | null;
  sampleEvents: Array<{
    id: string;
    traceId: string;
    otelTraceId: string | null;
    timestamp: number;
    latencyMs: number;
    status: string;
    error: string | null;
    componentTiming: {
      gatewayMs: number;
      kafkaMs: number;
      flinkMs: number;
      droolsMs: number;
    };
    traceData: {
      trace: { spans: unknown[] } | null;
      logs: Array<{
        timestamp: string;
        line: string;
        labels: Record<string, string>;
        fields: Record<string, string>;
      }>;
    } | null;
  }>;
  status: string;
  errorMessage: string | null;
  success?: boolean;
}

// ============================================================================
// Validation Result Types
// ============================================================================

export type IssueSeverity = 'critical' | 'warning' | 'info';
export type IssueCategory = 'benchmark_failure' | 'data_missing' | 'trace_missing' | 'performance' | 'infrastructure';

export interface ValidationIssue {
  id: string;
  category: IssueCategory;
  severity: IssueSeverity;
  title: string;
  description: string;
  evidence: string;
  fixSteps: string[];
  commands?: string[];
  relatedDocs?: string;
}

export interface DiagnosticResult {
  timestamp: string;
  component: string;
  overallStatus: 'healthy' | 'degraded' | 'failing';
  completenessScore: number;
  issues: ValidationIssue[];
  summary: {
    criticalCount: number;
    warningCount: number;
    infoCount: number;
    dataFields: {
      throughputTimeline: boolean;
      cpuTimeline: boolean;
      memoryTimeline: boolean;
      latency: boolean;
      sampleEvents: boolean;
      traces: boolean;
      logs: boolean;
    };
  };
}

// ============================================================================
// Diagnostic Functions
// ============================================================================

export function runDiagnostic(data: unknown): DiagnosticResult {
  const issues: ValidationIssue[] = [];
  const timestamp = new Date().toISOString();

  if (!data || typeof data !== 'object') {
    return {
      timestamp,
      component: 'unknown',
      overallStatus: 'failing',
      completenessScore: 0,
      issues: [{
        id: 'no-data',
        category: 'data_missing',
        severity: 'critical',
        title: 'No benchmark data found',
        description: 'The benchmark data object is missing or invalid',
        evidence: `Received: ${typeof data}`,
        fixSteps: [
          'Check that window.__BENCHMARK_DATA__ is properly set in the HTML',
          'Verify the benchmark completed successfully',
          'Check the results.json file exists in the report directory'
        ],
        commands: ['cat reports/<component>/results.json']
      }],
      summary: {
        criticalCount: 1,
        warningCount: 0,
        infoCount: 0,
        dataFields: {
          throughputTimeline: false,
          cpuTimeline: false,
          memoryTimeline: false,
          latency: false,
          sampleEvents: false,
          traces: false,
          logs: false,
        }
      }
    };
  }

  const d = data as BenchmarkData;
  const component = d.component || 'unknown';

  // ========================================================================
  // Check 1: Benchmark Failure Detection
  // ========================================================================

  const successRate = d.totalOperations > 0
    ? (d.successfulOperations / d.totalOperations) * 100
    : 0;
  const failureRate = d.totalOperations > 0
    ? (d.failedOperations / d.totalOperations) * 100
    : 0;

  if (failureRate === 100) {
    // Complete failure - all operations failed
    if (component === 'kafka') {
      issues.push({
        id: 'kafka-complete-failure',
        category: 'benchmark_failure',
        severity: 'critical',
        title: 'Kafka Benchmark: 100% Failure Rate',
        description: 'All Kafka operations failed. The consumer is not receiving responses from the counter-results topic.',
        evidence: `${d.failedOperations.toLocaleString()} failed, 0 successful. Peak throughput: ${d.peakThroughput}/s`,
        fixSteps: [
          '1. Check if Flink is processing messages from counter-events topic',
          '2. Check if Drools is receiving requests from Flink',
          '3. Check if results are being published to counter-results topic',
          '4. Verify Kafka topics exist and are accessible',
          '5. Check Flink TaskManager logs for errors'
        ],
        commands: [
          'docker compose logs flink-taskmanager --tail=50',
          'docker compose logs drools --tail=50',
          'docker compose exec kafka kafka-topics --list --bootstrap-server localhost:9092',
          'docker compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic counter-results --from-beginning --max-messages 5'
        ],
        relatedDocs: 'Check Flink job is running: http://localhost:8081'
      });
    } else if (component === 'full') {
      issues.push({
        id: 'full-complete-failure',
        category: 'benchmark_failure',
        severity: 'critical',
        title: 'Full E2E Benchmark: Complete Failure',
        description: 'All E2E operations failed. The pipeline is not responding.',
        evidence: `${d.failedOperations.toLocaleString()} failed, 0 successful`,
        fixSteps: [
          '1. Check if Gateway service is running and healthy',
          '2. Verify Kafka is accepting connections',
          '3. Check if Flink job is deployed and running',
          '4. Verify Drools service is healthy'
        ],
        commands: [
          'docker compose ps',
          'curl -s http://localhost:8080/health',
          'docker compose logs gateway --tail=20'
        ]
      });
    } else {
      issues.push({
        id: `${component}-complete-failure`,
        category: 'benchmark_failure',
        severity: 'critical',
        title: `${d.name}: Complete Failure`,
        description: 'All operations failed',
        evidence: `${d.failedOperations.toLocaleString()} failed, 0 successful`,
        fixSteps: [
          `1. Check if ${component} service is running`,
          '2. Check service logs for errors',
          '3. Verify network connectivity'
        ],
        commands: [
          `docker compose logs ${component} --tail=50`,
          `docker compose ps ${component}`
        ]
      });
    }
  } else if (failureRate > 50) {
    issues.push({
      id: `${component}-high-failure`,
      category: 'benchmark_failure',
      severity: 'critical',
      title: `${d.name}: High Failure Rate (${failureRate.toFixed(1)}%)`,
      description: 'More than half of operations are failing',
      evidence: `${d.failedOperations.toLocaleString()} failed out of ${d.totalOperations.toLocaleString()}`,
      fixSteps: [
        '1. Check service logs for error patterns',
        '2. Look for timeout or connection errors',
        '3. Check if downstream services are overloaded'
      ],
      commands: [
        `docker compose logs ${component} --tail=100 | grep -i error`
      ]
    });
  } else if (failureRate > 5) {
    issues.push({
      id: `${component}-elevated-failure`,
      category: 'benchmark_failure',
      severity: 'warning',
      title: `${d.name}: Elevated Failure Rate (${failureRate.toFixed(1)}%)`,
      description: 'Failure rate is above acceptable threshold (5%)',
      evidence: `${d.failedOperations.toLocaleString()} failed out of ${d.totalOperations.toLocaleString()}`,
      fixSteps: [
        '1. Investigate error messages in sample events',
        '2. Check for intermittent connectivity issues'
      ]
    });
  }

  // ========================================================================
  // Check 2: Zero Throughput Detection
  // ========================================================================

  const nonZeroThroughput = d.throughputTimeline?.filter(v => v > 0) || [];
  if (d.peakThroughput === 0 || nonZeroThroughput.length === 0) {
    issues.push({
      id: `${component}-zero-throughput`,
      category: 'benchmark_failure',
      severity: 'critical',
      title: 'Zero Throughput Recorded',
      description: 'No successful operations were recorded during the benchmark',
      evidence: `Peak: ${d.peakThroughput}/s, Avg: ${d.avgThroughput}/s, Timeline samples with data: ${nonZeroThroughput.length}`,
      fixSteps: [
        '1. The service may not be responding',
        '2. Check if the target endpoint is correct',
        '3. Verify the service is accepting the request format'
      ]
    });
  }

  // ========================================================================
  // Check 3: Data Completeness
  // ========================================================================

  const hasThroughputData = d.throughputTimeline?.length > 0 && nonZeroThroughput.length > 0;
  const hasCpuData = d.cpuTimeline?.length > 0;
  const hasMemoryData = d.memoryTimeline?.length > 0;
  const hasLatencyData = d.latency && (d.latency.p50 > 0 || d.latency.p99 > 0);
  const hasSampleEvents = d.sampleEvents?.length > 0;
  const hasTraces = d.sampleEvents?.some(e => (e.traceData?.trace?.spans?.length ?? 0) > 0);
  const hasLogs = d.sampleEvents?.some(e => (e.traceData?.logs?.length ?? 0) > 0);

  if (!hasThroughputData && d.totalOperations > 0) {
    issues.push({
      id: 'missing-throughput-timeline',
      category: 'data_missing',
      severity: 'warning',
      title: 'Throughput Timeline Empty',
      description: 'No throughput samples were recorded despite operations completing',
      evidence: `Timeline length: ${d.throughputTimeline?.length || 0}, Total ops: ${d.totalOperations}`,
      fixSteps: [
        '1. Check if metrics collection is running during benchmark',
        '2. Verify the benchmark duration is long enough for sampling'
      ]
    });
  }

  if (!hasCpuData) {
    issues.push({
      id: 'missing-cpu-timeline',
      category: 'data_missing',
      severity: 'warning',
      title: 'CPU Metrics Missing',
      description: 'No CPU utilization data was collected',
      evidence: `CPU timeline length: ${d.cpuTimeline?.length || 0}`,
      fixSteps: [
        '1. Check if cAdvisor is running and accessible',
        '2. Verify container metrics are being exported'
      ],
      commands: ['curl -s http://localhost:8085/metrics | grep container_cpu']
    });
  }

  if (!hasMemoryData) {
    issues.push({
      id: 'missing-memory-timeline',
      category: 'data_missing',
      severity: 'warning',
      title: 'Memory Metrics Missing',
      description: 'No memory utilization data was collected',
      evidence: `Memory timeline length: ${d.memoryTimeline?.length || 0}`,
      fixSteps: [
        '1. Check if cAdvisor is running and accessible',
        '2. Verify container metrics are being exported'
      ],
      commands: ['curl -s http://localhost:8085/metrics | grep container_memory']
    });
  }

  // ========================================================================
  // Check 4: Trace & Log Enrichment
  // ========================================================================

  if (!hasTraces && hasSampleEvents) {
    // Check if trace IDs exist but spans are empty (memory pressure issue)
    const hasOtelTraceIds = d.sampleEvents?.some(e => e.otelTraceId && e.otelTraceId.length > 0);

    issues.push({
      id: 'missing-traces',
      category: 'trace_missing',
      severity: 'warning',
      title: 'No Trace Data Available',
      description: hasOtelTraceIds
        ? 'Trace IDs are present but Jaeger returned 0 spans. This typically happens when otel-collector drops spans due to memory pressure during high-load benchmarks. The collector has a memory_limiter that refuses data when memory exceeds thresholds.'
        : 'Sample events do not contain Jaeger trace spans. otel-collector may not be running or traces are not being exported.',
      evidence: `${d.sampleEvents?.length || 0} events, 0 with trace spans${hasOtelTraceIds ? ', trace IDs present but empty in Jaeger' : ''}`,
      fixSteps: hasOtelTraceIds
        ? [
            '1. Restart otel-collector to free memory (most common fix)',
            '2. Restart Gateway so it reconnects to otel-collector',
            '3. Run a shorter/lighter benchmark to avoid memory pressure',
            '4. Check for "data refused due to high memory usage" in gateway logs',
            '5. Consider reducing sampling rate for high-load benchmarks',
            '6. Optionally increase OTEL_MEMORY env var (default: 1024M)'
          ]
        : [
            '1. Check if otel-collector is running (most common cause)',
            '2. Restart Gateway after starting otel-collector (required for reconnection)',
            '3. Check service logs for "Failed to export spans" or "Name or service not known" errors',
            '4. Verify Jaeger is receiving traces from otel-collector',
            '5. Run benchmark without --quick flag to enable enrichment'
          ],
      commands: hasOtelTraceIds
        ? [
            'docker compose restart otel-collector && sleep 5 && docker compose restart gateway',
            'docker compose logs gateway --tail=30 | grep -i "memory\\|refused\\|export"',
            'docker compose logs otel-collector --tail=20',
            'curl -s http://localhost:13133/health | jq .',
            '# Then re-run benchmark after services settle'
          ]
        : [
            'docker compose ps otel-collector',
            'docker compose up -d otel-collector && sleep 10 && docker compose restart gateway',
            'docker compose logs gateway --tail=30 | grep -i "export\\|otel\\|failed"',
            'curl -s "http://localhost:16686/api/services" | jq .data',
            'curl -s "http://localhost:16686/api/traces?service=counter-application&limit=3" | jq ".data[].traceID"'
          ],
      relatedDocs: hasOtelTraceIds
        ? 'otel-collector health: http://localhost:13133 | Config: platform/deployment/docker/observability/otel-collector-config.yaml'
        : 'Jaeger UI: http://localhost:16686 | otel-collector health: http://localhost:13133'
    });
  }

  if (!hasLogs && hasSampleEvents) {
    issues.push({
      id: 'missing-logs',
      category: 'trace_missing',
      severity: 'info',
      title: 'No Log Data Available',
      description: 'Sample events do not contain Loki log data. Promtail may not be collecting container logs.',
      evidence: `${d.sampleEvents?.length || 0} events, 0 with logs`,
      fixSteps: [
        '1. Verify Loki and Promtail are running',
        '2. Check Promtail is configured to collect Docker logs',
        '3. Run benchmark without --quick flag to enable enrichment',
        '4. Verify logs are being ingested by Loki'
      ],
      commands: [
        'docker compose ps loki promtail',
        'docker compose up -d loki promtail',
        'curl -s "http://localhost:3100/loki/api/v1/labels" | jq .',
        'curl -s "http://localhost:3100/ready"'
      ],
      relatedDocs: 'Grafana (Loki): http://localhost:3001'
    });
  }

  if (!hasSampleEvents) {
    issues.push({
      id: 'missing-sample-events',
      category: 'data_missing',
      severity: 'warning',
      title: 'No Sample Events Captured',
      description: 'The benchmark did not capture any sample events for analysis',
      evidence: 'sampleEvents array is empty',
      fixSteps: [
        '1. Check if benchmark completed successfully',
        '2. Ensure sample event collection is enabled in benchmark code'
      ]
    });
  }

  // ========================================================================
  // Check 5: Performance Issues
  // ========================================================================

  if (d.latency?.p99 > 1000 && successRate > 50) {
    issues.push({
      id: 'high-p99-latency',
      category: 'performance',
      severity: 'warning',
      title: `High P99 Latency: ${d.latency.p99}ms`,
      description: 'P99 latency exceeds 1 second, indicating performance issues',
      evidence: `P50: ${d.latency.p50}ms, P95: ${d.latency.p95}ms, P99: ${d.latency.p99}ms`,
      fixSteps: [
        '1. Check for GC pauses in Java services',
        '2. Look for lock contention in thread dumps',
        '3. Verify downstream services are not overloaded'
      ]
    });
  }

  if (d.peakMemory > 8 && hasMemoryData) {
    issues.push({
      id: 'high-memory-usage',
      category: 'performance',
      severity: 'warning',
      title: `High Memory Usage: ${d.peakMemory.toFixed(1)}GB`,
      description: 'Peak memory usage is high, risk of OOM',
      evidence: `Peak: ${d.peakMemory.toFixed(2)}GB, Avg: ${d.avgMemory.toFixed(2)}GB`,
      fixSteps: [
        '1. Check for memory leaks',
        '2. Review heap settings for Java services',
        '3. Consider increasing container memory limits'
      ]
    });
  }

  // ========================================================================
  // Calculate Summary
  // ========================================================================

  const criticalCount = issues.filter(i => i.severity === 'critical').length;
  const warningCount = issues.filter(i => i.severity === 'warning').length;
  const infoCount = issues.filter(i => i.severity === 'info').length;

  const dataFieldsPresent = [
    hasThroughputData,
    hasCpuData,
    hasMemoryData,
    hasLatencyData,
    hasSampleEvents,
    hasTraces,
    hasLogs,
  ].filter(Boolean).length;

  const completenessScore = (dataFieldsPresent / 7) * 100;

  const overallStatus: 'healthy' | 'degraded' | 'failing' =
    criticalCount > 0 ? 'failing' :
    warningCount > 0 ? 'degraded' :
    'healthy';

  return {
    timestamp,
    component,
    overallStatus,
    completenessScore,
    issues,
    summary: {
      criticalCount,
      warningCount,
      infoCount,
      dataFields: {
        throughputTimeline: hasThroughputData,
        cpuTimeline: hasCpuData,
        memoryTimeline: hasMemoryData,
        latency: hasLatencyData,
        sampleEvents: hasSampleEvents,
        traces: hasTraces,
        logs: hasLogs,
      }
    }
  };
}

// ============================================================================
// Generate Markdown Task List
// ============================================================================

export function generateTaskList(diagnostic: DiagnosticResult): string {
  if (diagnostic.issues.length === 0) {
    return `# ✅ ${diagnostic.component} Benchmark: All Checks Passed

Data completeness: ${diagnostic.completenessScore.toFixed(0)}%

All systems operational.
`;
  }

  let md = `# 🔧 ${diagnostic.component} Benchmark: Fix Task List

**Status:** ${diagnostic.overallStatus.toUpperCase()}
**Data Completeness:** ${diagnostic.completenessScore.toFixed(0)}%
**Generated:** ${diagnostic.timestamp}

---

`;

  const critical = diagnostic.issues.filter(i => i.severity === 'critical');
  const warnings = diagnostic.issues.filter(i => i.severity === 'warning');
  const info = diagnostic.issues.filter(i => i.severity === 'info');

  if (critical.length > 0) {
    md += `## 🚨 Critical Issues (${critical.length})\n\n`;
    critical.forEach((issue, i) => {
      md += `### ${i + 1}. ${issue.title}\n\n`;
      md += `**Problem:** ${issue.description}\n\n`;
      md += `**Evidence:** \`${issue.evidence}\`\n\n`;
      md += `**Fix Steps:**\n`;
      issue.fixSteps.forEach(step => {
        md += `- [ ] ${step}\n`;
      });
      if (issue.commands?.length) {
        md += `\n**Commands to run:**\n\`\`\`bash\n`;
        issue.commands.forEach(cmd => {
          md += `${cmd}\n`;
        });
        md += `\`\`\`\n`;
      }
      if (issue.relatedDocs) {
        md += `\n**Related:** ${issue.relatedDocs}\n`;
      }
      md += '\n---\n\n';
    });
  }

  if (warnings.length > 0) {
    md += `## ⚠️ Warnings (${warnings.length})\n\n`;
    warnings.forEach((issue, i) => {
      md += `### ${i + 1}. ${issue.title}\n\n`;
      md += `${issue.description}\n\n`;
      md += `**Evidence:** \`${issue.evidence}\`\n\n`;
      md += `**Fix Steps:**\n`;
      issue.fixSteps.forEach(step => {
        md += `- [ ] ${step}\n`;
      });
      if (issue.commands?.length) {
        md += `\n\`\`\`bash\n${issue.commands.join('\n')}\n\`\`\`\n`;
      }
      md += '\n';
    });
  }

  if (info.length > 0) {
    md += `## ℹ️ Info (${info.length})\n\n`;
    info.forEach((issue, i) => {
      md += `### ${i + 1}. ${issue.title}\n\n`;
      md += `${issue.description}\n\n`;
      md += `**Fix Steps:**\n`;
      issue.fixSteps.forEach(step => {
        md += `- [ ] ${step}\n`;
      });
      if (issue.commands?.length) {
        md += `\n\`\`\`bash\n${issue.commands.join('\n')}\n\`\`\`\n`;
      }
      md += '\n';
    });
  }

  return md;
}

// Legacy exports for backwards compatibility
export interface ValidationResult {
  field: string;
  path: string;
  expected: string;
  actual: string;
  severity: 'critical' | 'warning' | 'info';
  fixSuggestion: string;
}

export function validateBenchmarkData(data: unknown): ValidationResult[] {
  const diagnostic = runDiagnostic(data);
  return diagnostic.issues.map(issue => ({
    field: issue.title,
    path: issue.id,
    expected: issue.fixSteps[0] || 'See fix steps',
    actual: issue.evidence,
    severity: issue.severity,
    fixSuggestion: issue.description
  }));
}

export function generateFixTaskList(issues: ValidationResult[]): string {
  if (issues.length === 0) return 'All data is present and valid!';

  let taskList = '# Data Alignment Task List\n\n';

  const critical = issues.filter(i => i.severity === 'critical');
  const warnings = issues.filter(i => i.severity === 'warning');

  if (critical.length > 0) {
    taskList += '## Critical Issues\n\n';
    critical.forEach((issue, i) => {
      taskList += `${i + 1}. **${issue.field}**\n`;
      taskList += `   - ${issue.fixSuggestion}\n`;
      taskList += `   - Evidence: ${issue.actual}\n\n`;
    });
  }

  if (warnings.length > 0) {
    taskList += '## Warnings\n\n';
    warnings.forEach((issue, i) => {
      taskList += `${i + 1}. **${issue.field}**\n`;
      taskList += `   - ${issue.fixSuggestion}\n\n`;
    });
  }

  return taskList;
}
