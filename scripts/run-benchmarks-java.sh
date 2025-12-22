#!/bin/bash
# Run Java-based benchmarks and generate HTML reports
#
# Usage:
#   ./scripts/run-benchmarks-java.sh <component> [--duration <seconds>]
#
# Components: http, kafka, flink, drools, gateway, full, all
#
# This script:
#   1. Compiles the Java benchmark code (via Docker Maven)
#   2. Runs the specified benchmark
#   3. Generates JSON output to reports/<component>/results.json
#   4. Creates HTML report that loads the React bundle

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPORTS_DIR="$PROJECT_DIR/reports"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }

# Default values
COMPONENT="${1:-help}"
DURATION=60
CONCURRENCY=8
QUICK_MODE=false
SKIP_ENRICHMENT=false

# Docker Compose network name (project name + network name)
DOCKER_NETWORK="reactive-system-01_reactive-network"

# URLs for Docker Compose services (inside the network)
GATEWAY_URL="http://gateway:3000"
DROOLS_URL="http://drools:8080"
JAEGER_URL="http://jaeger:16686"
LOKI_URL="http://loki:3100"

# Parse arguments
shift 2>/dev/null || true
while [[ $# -gt 0 ]]; do
    case "$1" in
        --duration|-d)
            DURATION="$2"
            shift 2
            ;;
        --concurrency|-c)
            CONCURRENCY="$2"
            shift 2
            ;;
        --gateway-url)
            GATEWAY_URL="$2"
            shift 2
            ;;
        --drools-url)
            DROOLS_URL="$2"
            shift 2
            ;;
        --quick|-q)
            QUICK_MODE=true
            DURATION=5
            SKIP_ENRICHMENT=true
            shift
            ;;
        --skip-enrichment)
            SKIP_ENRICHMENT=true
            shift
            ;;
        *)
            shift
            ;;
    esac
done

show_help() {
    echo ""
    echo "Java Benchmark Runner (Docker Compose)"
    echo "======================================="
    echo ""
    echo "Usage: ./scripts/run-benchmarks-java.sh <component> [options]"
    echo ""
    echo "Components:"
    echo "  http      HTTP endpoint latency (gateway health check)"
    echo "  kafka     Kafka produce/consume round-trip"
    echo "  flink     Flink stream processing throughput"
    echo "  drools    Direct Drools rule evaluation"
    echo "  gateway   HTTP + Kafka publish (fire-and-forget)"
    echo "  full      Complete E2E pipeline (HTTP → Kafka → Flink → Drools)"
    echo "  all       Run all benchmarks"
    echo ""
    echo "Options:"
    echo "  --duration, -d <sec>    Benchmark duration (default: 60)"
    echo "  --concurrency, -c <n>   Concurrent workers (default: 8)"
    echo "  --quick, -q             Quick mode: 5s duration, skip enrichment"
    echo "  --skip-enrichment       Skip trace/log fetching (faster)"
    echo "  --gateway-url <url>     Gateway URL (default: http://gateway:3000)"
    echo "  --drools-url <url>      Drools URL (default: http://drools:8080)"
    echo ""
    echo "Examples:"
    echo "  ./scripts/run-benchmarks-java.sh full --quick"
    echo "  ./scripts/run-benchmarks-java.sh drools --duration 60"
    echo "  ./scripts/run-benchmarks-java.sh full --duration 120"
    echo "  ./scripts/run-benchmarks-java.sh all"
    echo ""
    echo "Output:"
    echo "  Reports are generated to: reports/<component>/index.html"
    echo ""
    echo "Prerequisites:"
    echo "  Docker Compose stack must be running: docker compose up -d"
    echo ""
}

# Get Docker host for commands - check for colima socket locations
if [[ -S "$HOME/.colima/default/docker.sock" ]]; then
    DOCKER_HOST_CMD="unix://$HOME/.colima/default/docker.sock"
elif [[ -S "$HOME/.colima/docker.sock" ]]; then
    DOCKER_HOST_CMD="unix://$HOME/.colima/docker.sock"
elif [[ -n "$DOCKER_HOST" ]]; then
    DOCKER_HOST_CMD="$DOCKER_HOST"
else
    DOCKER_HOST_CMD="unix:///var/run/docker.sock"
fi
export DOCKER_HOST="$DOCKER_HOST_CMD"

# Kafka bootstrap servers (inside Docker network)
KAFKA_BOOTSTRAP_SERVERS="kafka:29092"

# Docker Maven command connected to Docker Compose network
docker_maven() {
    docker run --rm \
        -v "$PROJECT_DIR":/app \
        -v maven-repo:/root/.m2 \
        -w /app \
        --network "$DOCKER_NETWORK" \
        -e JAEGER_QUERY_URL="$JAEGER_URL" \
        -e LOKI_URL="$LOKI_URL" \
        -e KAFKA_BOOTSTRAP_SERVERS="$KAFKA_BOOTSTRAP_SERVERS" \
        maven:3.9-eclipse-temurin-21 \
        "$@"
}

# Check if Docker Compose services are running
check_services() {
    local component="$1"

    print_info "Checking Docker Compose services..."

    # Check if network exists
    if ! docker network ls | grep -q "$DOCKER_NETWORK"; then
        print_error "Docker Compose network not found: $DOCKER_NETWORK"
        print_error "Please start the stack first: docker compose up -d"
        exit 1
    fi

    # Check required services based on component
    local services_to_check=("jaeger" "loki")

    if [[ "$component" == "drools" || "$component" == "all" ]]; then
        services_to_check+=("reactive-drools")
    fi

    if [[ "$component" == "gateway" || "$component" == "all" ]]; then
        services_to_check+=("reactive-application")
    fi

    if [[ "$component" == "kafka" || "$component" == "flink" || "$component" == "full" || "$component" == "all" ]]; then
        services_to_check+=("reactive-kafka" "reactive-flink-jobmanager")
    fi

    if [[ "$component" == "full" || "$component" == "all" ]]; then
        services_to_check+=("reactive-application")
    fi

    for service in "${services_to_check[@]}"; do
        if ! docker ps --format '{{.Names}}' | grep -q "$service"; then
            print_warning "Service not running: $service"
        fi
    done

    print_info "Services check complete"
}

# Generate HTML report from JSON
generate_html_report() {
    local component="$1"
    local component_dir="$REPORTS_DIR/$component"
    local json_file="$component_dir/results.json"
    local html_file="$component_dir/index.html"

    if [[ ! -f "$json_file" ]]; then
        print_error "JSON file not found: $json_file"
        return 1
    fi

    # Get git commit if available
    local commit="unknown"
    if command -v git &>/dev/null; then
        commit=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    fi

    # Read JSON and get component name
    local name
    name=$(python3 -c "import json; print(json.load(open('$json_file'))['name'])" 2>/dev/null || echo "$component Benchmark")

    # Create HTML that embeds JSON and loads React bundle
    cat > "$html_file" << EOF
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$name Report</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📊</text></svg>" />
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        #root { min-height: 100vh; }
        .loading { display: flex; justify-content: center; align-items: center; height: 100vh; font-size: 18px; color: #666; }
    </style>
</head>
<body>
    <div id="root"><div class="loading">Loading benchmark report...</div></div>
    <script>
        window.__BENCHMARK_DATA__ = $(cat "$json_file");
        window.__BENCHMARK_COMMIT__ = "$commit";
    </script>
    <script src="../assets/benchmark-report.js"></script>
</body>
</html>
EOF

    print_success "HTML report generated: $html_file"
}

# Generate dashboard index.html with React-based UI
generate_dashboard_index() {
    local index_file="$REPORTS_DIR/index.html"
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')

    print_info "Generating React-based dashboard..."

    # Use Python to generate the dashboard with embedded JSON data
    python3 << EOF
import json
import os
from pathlib import Path
from datetime import datetime

reports_dir = Path("$REPORTS_DIR")
index_file = reports_dir / "index.html"

# Collect available reports
components = []
rankings = []

for comp in ["full", "http", "kafka", "flink", "drools", "gateway"]:
    json_path = reports_dir / comp / "results.json"
    if json_path.exists():
        with open(json_path) as f:
            data = json.load(f)
        components.append({
            "id": comp,
            "name": data.get("name", f"{comp} Benchmark"),
            "peakThroughput": data.get("peakThroughput", 0),
            "avgThroughput": data.get("avgThroughput", 0),
            "latencyP99": data.get("latency", {}).get("p99", 0),
            "status": data.get("status", "completed")
        })

if not components:
    print("No reports found to generate dashboard")
    exit(0)

# Calculate rankings (sorted by throughput, identify bottleneck)
max_throughput = max(c["peakThroughput"] for c in components) or 1
sorted_components = sorted(components, key=lambda x: x["peakThroughput"])

for i, comp in enumerate(sorted_components):
    rankings.append({
        "id": comp["id"],
        "name": comp["name"],
        "peakThroughput": comp["peakThroughput"],
        "barWidth": int(comp["peakThroughput"] * 100 / max_throughput),
        "isBottleneck": i == 0  # Lowest throughput is bottleneck
    })

# Build index data
index_data = {
    "components": components,
    "rankings": rankings,
    "generatedAt": "$timestamp"
}

# Generate HTML with embedded data that uses the React bundle
html = f'''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Benchmark Dashboard</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>📊</text></svg>" />
    <style>
        * {{ margin: 0; padding: 0; box-sizing: border-box; }}
        body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }}
        #root {{ min-height: 100vh; }}
        .loading {{ display: flex; justify-content: center; align-items: center; height: 100vh; font-size: 18px; color: #666; }}
    </style>
</head>
<body>
    <div id="root"><div class="loading">Loading benchmark dashboard...</div></div>
    <script>
        window.__BENCHMARK_INDEX__ = {json.dumps(index_data, indent=2)};
    </script>
    <script src="assets/benchmark-index.js"></script>
    <script src="assets/regression-overlay.js"></script>
</body>
</html>
'''

with open(index_file, 'w') as f:
    f.write(html)

print(f"React dashboard generated with {len(components)} components")
EOF

    if [[ $? -eq 0 ]]; then
        print_success "Dashboard generated: $index_file"
    else
        print_error "Failed to generate dashboard"
    fi
}

# Run a single benchmark
run_benchmark() {
    local component="$1"
    local test_class=""
    local module=""

    case "$component" in
        http)
            test_class="com.reactive.gateway.benchmark.HttpBenchmark"
            module="gateway"
            ;;
        kafka)
            test_class="com.reactive.counter.benchmark.KafkaBenchmark"
            module="application"
            ;;
        flink)
            test_class="com.reactive.counter.benchmark.FlinkBenchmark"
            module="application"
            ;;
        drools)
            test_class="com.reactive.drools.benchmark.DroolsBenchmark"
            module="drools"
            ;;
        gateway)
            test_class="com.reactive.gateway.benchmark.GatewayBenchmark"
            module="gateway"
            ;;
        full)
            test_class="com.reactive.counter.benchmark.FullBenchmark"
            module="application"
            ;;
        *)
            print_error "Unknown component: $component"
            exit 1
            ;;
    esac

    local mode_info=""
    if [[ "$QUICK_MODE" == "true" ]]; then
        mode_info=" [QUICK MODE]"
    elif [[ "$SKIP_ENRICHMENT" == "true" ]]; then
        mode_info=" [skip enrichment]"
    fi
    print_info "Running $component benchmark (duration: ${DURATION}s, concurrency: $CONCURRENCY)$mode_info"

    # Check Docker Compose services are running
    check_services "$component"

    # Create output directory
    local component_dir="$REPORTS_DIR/$component"
    mkdir -p "$component_dir"

    # Create a simple Java runner class
    local runner_java="$PROJECT_DIR/BenchmarkRunner.java"
    cat > "$runner_java" << 'JAVA_EOF'
import com.reactive.platform.benchmark.*;
import com.reactive.platform.benchmark.BenchmarkTypes.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;

public class BenchmarkRunner {
    public static void main(String[] args) throws Exception {
        String component = args[0];
        long durationMs = Long.parseLong(args[1]) * 1000;
        int concurrency = Integer.parseInt(args[2]);
        String gatewayUrl = args[3];
        String droolsUrl = args[4];
        String outputDir = args[5];
        boolean quickMode = args.length > 6 && "true".equals(args[6]);
        boolean skipEnrichment = args.length > 7 && "true".equals(args[7]);

        Config.Builder configBuilder = Config.builder()
            .durationMs(durationMs)
            .concurrency(concurrency)
            .gatewayUrl(gatewayUrl)
            .droolsUrl(droolsUrl);

        if (quickMode) {
            configBuilder.quick();  // 5s, 1s warmup, no cooldown, skip enrichment
        } else if (skipEnrichment) {
            configBuilder.skipEnrichment(true);
        }

        Config config = configBuilder.build();

        BaseBenchmark benchmark;
        switch (component) {
            case "http" -> benchmark = (BaseBenchmark) Class.forName("com.reactive.gateway.benchmark.HttpBenchmark")
                .getMethod("create").invoke(null);
            case "kafka" -> benchmark = (BaseBenchmark) Class.forName("com.reactive.counter.benchmark.KafkaBenchmark")
                .getMethod("create").invoke(null);
            case "flink" -> benchmark = (BaseBenchmark) Class.forName("com.reactive.counter.benchmark.FlinkBenchmark")
                .getMethod("create").invoke(null);
            case "drools" -> benchmark = (BaseBenchmark) Class.forName("com.reactive.drools.benchmark.DroolsBenchmark")
                .getMethod("create").invoke(null);
            case "gateway" -> benchmark = (BaseBenchmark) Class.forName("com.reactive.gateway.benchmark.GatewayBenchmark")
                .getMethod("create").invoke(null);
            case "full" -> benchmark = (BaseBenchmark) Class.forName("com.reactive.counter.benchmark.FullBenchmark")
                .getMethod("create").invoke(null);
            default -> throw new IllegalArgumentException("Unknown: " + component);
        }

        System.out.println("Running " + benchmark.name() + "...");
        System.out.println("Duration: " + (config.durationMs() / 1000) + "s" + (config.quickMode() ? " (quick mode)" : ""));
        System.out.println("Concurrency: " + concurrency);
        if (config.skipEnrichment()) System.out.println("Enrichment: skipped");
        System.out.println("Drools URL: " + droolsUrl);
        System.out.println();

        BenchmarkResult result = benchmark.run(config);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);

        Path outputPath = Path.of(outputDir, component, "results.json");
        Files.createDirectories(outputPath.getParent());
        mapper.writeValue(outputPath.toFile(), result);

        System.out.println();
        System.out.println("=== Results ===");
        System.out.println("Status: " + result.status());
        System.out.println("Total operations: " + result.totalOperations());
        System.out.println("Successful: " + result.successfulOperations());
        System.out.println("Failed: " + result.failedOperations());
        System.out.println("Avg throughput: " + result.avgThroughput() + " ops/sec");
        System.out.println("Peak throughput: " + result.peakThroughput() + " ops/sec");
        System.out.printf("Stability: %.2f (CV - lower is more stable)%n", result.throughputStability());
        System.out.println("Latency P50: " + result.latency().p50() + "ms");
        System.out.println("Latency P99: " + result.latency().p99() + "ms");

        // Rich diagnostic report (only if trace data available)
        var report = result.generateDiagnosticReport();
        if (report != null && report.traceCount() > 0) {
            System.out.println(report.toConsoleReport());
        }

        System.out.println();
        System.out.println("JSON output: " + outputPath);
    }
}
JAVA_EOF

    # Compile and install platform first (needed for benchmark profile dependency)
    print_info "Compiling modules..."
    docker_maven mvn -f platform/pom.xml install -q -DskipTests || {
        print_error "Failed to compile platform module"
        rm -f "$runner_java"
        exit 1
    }

    docker_maven mvn -f "$module/pom.xml" compile test-compile -q -DskipTests -Pbenchmark || {
        print_error "Failed to compile $module module"
        rm -f "$runner_java"
        exit 1
    }

    print_info "Building classpath..."

    # Get classpath (with benchmark profile for platform dependency)
    local classpath
    classpath=$(docker_maven mvn -f "$module/pom.xml" dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q -Pbenchmark 2>/dev/null | tail -1)

    # Compile runner
    print_info "Compiling benchmark runner..."
    docker_maven javac -cp "platform/target/classes:$module/target/classes:$module/target/test-classes:$classpath" \
        -d /app BenchmarkRunner.java || {
        print_error "Failed to compile BenchmarkRunner"
        rm -f "$runner_java"
        exit 1
    }

    # Run benchmark
    print_info "Starting benchmark..."
    docker_maven java -cp ".:platform/target/classes:$module/target/classes:$module/target/test-classes:$classpath" \
        BenchmarkRunner "$component" "$DURATION" "$CONCURRENCY" "$GATEWAY_URL" "$DROOLS_URL" "/app/reports" "$QUICK_MODE" "$SKIP_ENRICHMENT" || {
        print_error "Benchmark failed"
        rm -f "$runner_java" "$PROJECT_DIR/BenchmarkRunner.class"
        exit 1
    }

    # Cleanup files
    rm -f "$runner_java" "$PROJECT_DIR/BenchmarkRunner.class"

    # Generate HTML report
    generate_html_report "$component"
}

# Main
case "$COMPONENT" in
    help|--help|-h)
        show_help
        exit 0
        ;;
    http|kafka|flink|drools|gateway|full)
        run_benchmark "$COMPONENT"
        generate_dashboard_index
        echo ""
        print_success "Benchmark complete!"
        print_info "Open report: open $REPORTS_DIR/$COMPONENT/index.html"
        print_info "Open dashboard: open $REPORTS_DIR/index.html"
        ;;
    all)
        for comp in http kafka flink drools gateway full; do
            echo ""
            echo "========================================"
            echo "Running $comp benchmark"
            echo "========================================"
            run_benchmark "$comp"
        done
        generate_dashboard_index

        # Save to history and generate regression report
        echo ""
        print_info "Saving benchmark results to history..."
        "$SCRIPT_DIR/benchmark-history.sh" save 2>/dev/null || true
        "$SCRIPT_DIR/benchmark-history.sh" report 2>/dev/null || true

        # Show comparison with previous run
        echo ""
        "$SCRIPT_DIR/benchmark-history.sh" compare 2>/dev/null || true

        echo ""
        print_success "All benchmarks complete!"
        print_info "Open dashboard: open $REPORTS_DIR/index.html"
        ;;
    *)
        print_error "Unknown component: $COMPONENT"
        show_help
        exit 1
        ;;
esac
