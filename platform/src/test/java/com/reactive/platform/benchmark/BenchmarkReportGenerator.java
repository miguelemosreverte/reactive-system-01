package com.reactive.platform.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Generates HTML and Markdown benchmark reports.
 */
public class BenchmarkReportGenerator {

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Generate both HTML and Markdown reports from benchmark result.
     */
    public static void generateReports(BenchmarkResult result, Path directory) throws IOException {
        Files.createDirectories(directory);

        // Generate HTML
        Path htmlPath = directory.resolve(result.component() + "-benchmark.html");
        Files.writeString(htmlPath, generateHtml(result));

        // Generate Markdown
        Path mdPath = directory.resolve(result.component() + "-benchmark.md");
        Files.writeString(mdPath, generateMarkdown(result));

        // Generate JSON (for programmatic access)
        Path jsonPath = directory.resolve(result.component() + "-benchmark.json");
        Files.writeString(jsonPath, mapper.writeValueAsString(result));

        System.out.println("Reports generated:");
        System.out.println("  HTML: " + htmlPath);
        System.out.println("  Markdown: " + mdPath);
        System.out.println("  JSON: " + jsonPath);
    }

    /**
     * Generate HTML report from benchmark result.
     */
    public static void generateReport(BenchmarkResult result, Path outputPath) throws IOException {
        String html = generateHtml(result);
        Files.writeString(outputPath, html);
    }

    /**
     * Generate Markdown report from benchmark result.
     */
    public static String generateMarkdown(BenchmarkResult result) {
        StringBuilder md = new StringBuilder();

        md.append("# ").append(result.component()).append(" Benchmark Report\n\n");
        md.append("**Date:** ").append(TIME_FORMAT.format(result.startTime().atZone(java.time.ZoneId.systemDefault()))).append("\n");
        md.append("**Duration:** ").append(result.durationMs()).append("ms\n");
        md.append("**Status:** ").append(result.status()).append("\n\n");

        // Summary
        md.append("## Summary\n\n");
        md.append("| Metric | Value |\n");
        md.append("|--------|-------|\n");
        md.append("| Total Operations | ").append(String.format("%,d", result.totalOperations())).append(" |\n");
        md.append("| Successful | ").append(String.format("%,d", result.successfulOperations())).append(" |\n");
        md.append("| Failed | ").append(String.format("%,d", result.failedOperations())).append(" |\n");
        md.append("| Success Rate | ").append(String.format("%.2f%%", 100.0 * result.successfulOperations() / Math.max(1, result.totalOperations()))).append(" |\n");
        md.append("| **Peak Throughput** | **").append(String.format("%,.0f", result.peakThroughput())).append(" ops/sec** |\n");
        md.append("| Avg Throughput | ").append(String.format("%,.0f", result.avgThroughput())).append(" ops/sec |\n\n");

        // Latency
        md.append("## Latency Distribution\n\n");
        md.append("| Percentile | Latency |\n");
        md.append("|------------|--------:|\n");
        md.append("| Min | ").append(String.format("%.2f ms", result.latency().min())).append(" |\n");
        md.append("| P50 | ").append(String.format("%.2f ms", result.latency().p50())).append(" |\n");
        md.append("| P95 | ").append(String.format("%.2f ms", result.latency().p95())).append(" |\n");
        md.append("| P99 | ").append(String.format("%.2f ms", result.latency().p99())).append(" |\n");
        md.append("| Max | ").append(String.format("%.2f ms", result.latency().max())).append(" |\n");
        md.append("| Avg | ").append(String.format("%.2f ms", result.latency().avg())).append(" |\n\n");

        // Bottleneck Analysis
        md.append("## Bottleneck Analysis\n\n");
        md.append(analyzeBottlenecks(result));
        md.append("\n");

        // Recommendations
        md.append("## Recommendations\n\n");
        md.append(generateRecommendations(result));
        md.append("\n");

        // Sample Events (if any)
        if (!result.sampleEvents().isEmpty()) {
            md.append("## Sample Events\n\n");
            md.append("| Event ID | Latency | Status |\n");
            md.append("|----------|--------:|--------|\n");
            for (var event : result.sampleEvents()) {
                md.append("| ").append(event.id().substring(0, Math.min(8, event.id().length()))).append("... | ");
                md.append(event.latencyMs()).append("ms | ");
                md.append(event.status()).append(" |\n");
            }
            md.append("\n");
        }

        return md.toString();
    }

    private static String analyzeBottlenecks(BenchmarkResult result) {
        StringBuilder analysis = new StringBuilder();

        double p99 = result.latency().p99();
        double p50 = result.latency().p50();
        double throughput = result.peakThroughput();
        double successRate = 100.0 * result.successfulOperations() / Math.max(1, result.totalOperations());

        // Latency analysis
        if (p99 > 100) {
            analysis.append("- **HIGH LATENCY DETECTED**: P99 latency is ").append(String.format("%.0f", p99)).append("ms (>100ms threshold)\n");
            analysis.append("  - Possible causes: Network I/O, serialization overhead, GC pauses\n");
        } else if (p99 > 50) {
            analysis.append("- **MODERATE LATENCY**: P99 latency is ").append(String.format("%.0f", p99)).append("ms\n");
        } else {
            analysis.append("- ✅ Latency is within acceptable bounds (P99 < 50ms)\n");
        }

        // Tail latency analysis
        double tailRatio = p99 / Math.max(1, p50);
        if (tailRatio > 10) {
            analysis.append("- **TAIL LATENCY ISSUE**: P99/P50 ratio is ").append(String.format("%.1f", tailRatio)).append("x (should be < 5x)\n");
            analysis.append("  - Indicates inconsistent performance, possibly GC or resource contention\n");
        }

        // Throughput analysis
        if (throughput < 1000) {
            analysis.append("- **LOW THROUGHPUT**: ").append(String.format("%.0f", throughput)).append(" ops/sec is below typical targets\n");
            analysis.append("  - Consider: batching, parallel processing, connection pooling\n");
        } else if (throughput < 10000) {
            analysis.append("- **MODERATE THROUGHPUT**: ").append(String.format("%.0f", throughput)).append(" ops/sec\n");
        } else {
            analysis.append("- ✅ Good throughput: ").append(String.format("%,.0f", throughput)).append(" ops/sec\n");
        }

        // Error rate
        if (successRate < 99.9) {
            analysis.append("- **ERRORS DETECTED**: Success rate is ").append(String.format("%.2f", successRate)).append("%\n");
            analysis.append("  - ").append(result.failedOperations()).append(" failed operations need investigation\n");
        } else {
            analysis.append("- ✅ Excellent reliability: ").append(String.format("%.2f", successRate)).append("% success rate\n");
        }

        return analysis.toString();
    }

    private static String generateRecommendations(BenchmarkResult result) {
        StringBuilder recs = new StringBuilder();

        double p99 = result.latency().p99();
        double throughput = result.peakThroughput();

        if (p99 > 100) {
            recs.append("1. **Reduce latency**: Profile the hot path to identify slow operations\n");
            recs.append("   - Consider async I/O for network operations\n");
            recs.append("   - Use binary serialization (Avro) instead of JSON for Kafka messages\n");
        }

        if (throughput < 10000) {
            recs.append("2. **Increase throughput**:\n");
            recs.append("   - Increase Kafka batch size and linger.ms\n");
            recs.append("   - Add more parallelism (increase partitions and consumers)\n");
            recs.append("   - Use connection pooling for HTTP clients\n");
        }

        if (result.latency().p99() / Math.max(1, result.latency().p50()) > 5) {
            recs.append("3. **Reduce tail latency**:\n");
            recs.append("   - Tune JVM GC settings (-XX:MaxGCPauseMillis=10)\n");
            recs.append("   - Pre-allocate buffers to avoid allocation during hot path\n");
            recs.append("   - Consider using off-heap memory for large datasets\n");
        }

        if (recs.length() == 0) {
            recs.append("✅ Performance looks good! Consider:\n");
            recs.append("- Stress testing with higher concurrency\n");
            recs.append("- Testing with larger message sizes\n");
            recs.append("- Long-running stability tests\n");
        }

        return recs.toString();
    }

    /**
     * Generate HTML report for multiple results (multi-component).
     */
    public static void generateReport(List<BenchmarkResult> results, Path outputPath) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append(HTML_HEAD);

        html.append("<nav class=\"sidebar\">\n");
        html.append("  <h1>Benchmarks</h1>\n");
        html.append("  <ul>\n");
        for (BenchmarkResult r : results) {
            html.append(String.format("    <li data-component=\"%s\">%s</li>\n",
                    r.component(), r.component()));
        }
        html.append("  </ul>\n");
        html.append("</nav>\n");

        html.append("<main>\n");
        for (BenchmarkResult r : results) {
            html.append(String.format("<section id=\"%s\" class=\"report\">\n", r.component()));
            html.append(generateResultSection(r));
            html.append("</section>\n");
        }
        html.append("</main>\n");

        html.append(HTML_FOOT);
        Files.writeString(outputPath, html.toString());
    }

    private static String generateHtml(BenchmarkResult result) {
        return HTML_HEAD + generateResultSection(result) + HTML_FOOT;
    }

    private static String generateResultSection(BenchmarkResult result) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append(String.format("<h1>%s Benchmark Report</h1>\n", result.component()));
        sb.append(String.format("<p class=\"meta\">%s | Duration: %dms | Status: %s</p>\n",
                TIME_FORMAT.format(result.startTime().atZone(java.time.ZoneId.systemDefault())),
                result.durationMs(),
                result.status()));

        // Summary cards
        sb.append("<div class=\"cards\">\n");
        sb.append(card("Total Operations", String.valueOf(result.totalOperations())));
        sb.append(card("Success Rate",
                String.format("%.1f%%", 100.0 * result.successfulOperations() / Math.max(1, result.totalOperations()))));
        sb.append(card("Peak Throughput", String.format("%.0f/s", result.peakThroughput())));
        sb.append(card("Avg Throughput", String.format("%.0f/s", result.avgThroughput())));
        sb.append(card("P95 Latency", String.format("%.2fms", result.latency().p95())));
        sb.append(card("P99 Latency", String.format("%.2fms", result.latency().p99())));
        sb.append("</div>\n");

        // Latency stats
        sb.append("<h2>Latency Distribution</h2>\n");
        sb.append("<table>\n");
        sb.append("<tr><th>Min</th><th>Avg</th><th>P50</th><th>P95</th><th>P99</th><th>Max</th></tr>\n");
        sb.append(String.format("<tr><td>%.2fms</td><td>%.2fms</td><td>%.2fms</td><td>%.2fms</td><td>%.2fms</td><td>%.2fms</td></tr>\n",
                result.latency().min(),
                result.latency().avg(),
                result.latency().p50(),
                result.latency().p95(),
                result.latency().p99(),
                result.latency().max()));
        sb.append("</table>\n");

        // Throughput chart
        if (!result.throughputTimeline().isEmpty()) {
            sb.append("<h2>Throughput Timeline</h2>\n");
            sb.append("<div class=\"chart\" id=\"throughput-chart\"></div>\n");
            sb.append("<script>\n");
            sb.append(String.format("renderChart('throughput-chart', %s, 'Throughput (events/sec)');\n",
                    result.throughputTimeline()));
            sb.append("</script>\n");
        }

        // Sample events
        if (!result.sampleEvents().isEmpty()) {
            sb.append("<h2>Sample Events</h2>\n");
            sb.append("<table>\n");
            sb.append("<tr><th>Event ID</th><th>Trace ID</th><th>Latency</th><th>Status</th></tr>\n");
            for (BenchmarkResult.SampleEvent event : result.sampleEvents()) {
                sb.append(String.format("<tr><td>%s</td><td><code>%s</code></td><td>%dms</td><td>%s</td></tr>\n",
                        event.id(),
                        event.traceId() != null ? event.traceId().substring(0, 16) + "..." : "N/A",
                        event.latencyMs(),
                        event.status()));
            }
            sb.append("</table>\n");
        }

        // JSON data
        sb.append("<h2>Raw Data</h2>\n");
        sb.append("<details>\n");
        sb.append("<summary>View JSON</summary>\n");
        sb.append("<pre>");
        try {
            sb.append(mapper.writeValueAsString(result));
        } catch (Exception e) {
            sb.append("Error serializing result");
        }
        sb.append("</pre>\n");
        sb.append("</details>\n");

        return sb.toString();
    }

    private static String card(String label, String value) {
        return String.format("""
            <div class="card">
              <div class="value">%s</div>
              <div class="label">%s</div>
            </div>
            """, value, label);
    }

    private static final String HTML_HEAD = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="UTF-8">
          <title>Benchmark Report</title>
          <style>
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0d1117; color: #c9d1d9; }
            .sidebar { position: fixed; left: 0; top: 0; width: 200px; height: 100vh; background: #161b22; padding: 20px; border-right: 1px solid #30363d; }
            .sidebar h1 { font-size: 16px; margin-bottom: 20px; color: #58a6ff; }
            .sidebar ul { list-style: none; }
            .sidebar li { padding: 10px; cursor: pointer; border-radius: 6px; margin-bottom: 4px; }
            .sidebar li:hover { background: #21262d; }
            .sidebar li.active { background: #388bfd33; color: #58a6ff; }
            main { margin-left: 220px; padding: 40px; }
            h1 { font-size: 24px; margin-bottom: 10px; }
            h2 { font-size: 18px; margin: 30px 0 15px; color: #8b949e; }
            .meta { color: #8b949e; margin-bottom: 30px; }
            .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 16px; margin-bottom: 30px; }
            .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 20px; text-align: center; }
            .card .value { font-size: 28px; font-weight: bold; color: #58a6ff; }
            .card .label { font-size: 12px; color: #8b949e; margin-top: 8px; }
            table { width: 100%; border-collapse: collapse; margin-bottom: 20px; }
            th, td { padding: 12px; text-align: left; border-bottom: 1px solid #30363d; }
            th { background: #161b22; color: #8b949e; font-weight: 500; }
            code { background: #161b22; padding: 2px 6px; border-radius: 4px; font-family: 'JetBrains Mono', monospace; }
            pre { background: #161b22; padding: 16px; border-radius: 8px; overflow-x: auto; font-size: 12px; }
            details { margin-top: 20px; }
            summary { cursor: pointer; color: #58a6ff; }
            .chart { height: 200px; background: #161b22; border-radius: 8px; padding: 20px; margin-bottom: 20px; }
          </style>
        </head>
        <body>
        """;

    private static final String HTML_FOOT = """
        <script>
        function renderChart(id, data, label) {
          const container = document.getElementById(id);
          if (!container || !data || data.length === 0) return;

          const max = Math.max(...data);
          const width = container.clientWidth - 40;
          const height = 160;
          const barWidth = Math.max(2, width / data.length - 1);

          let svg = '<svg width="' + width + '" height="' + height + '">';
          data.forEach((val, i) => {
            const h = (val / max) * height;
            const x = i * (barWidth + 1);
            svg += '<rect x="' + x + '" y="' + (height - h) + '" width="' + barWidth + '" height="' + h + '" fill="#58a6ff"/>';
          });
          svg += '</svg>';
          container.innerHTML = svg;
        }

        // Sidebar navigation
        document.querySelectorAll('.sidebar li').forEach(li => {
          li.addEventListener('click', () => {
            document.querySelectorAll('.sidebar li').forEach(l => l.classList.remove('active'));
            li.classList.add('active');
            const component = li.dataset.component;
            document.querySelectorAll('.report').forEach(r => {
              r.style.display = r.id === component ? 'block' : 'none';
            });
          });
        });

        // Show first report
        const firstLi = document.querySelector('.sidebar li');
        if (firstLi) firstLi.click();
        </script>
        </body>
        </html>
        """;
}
