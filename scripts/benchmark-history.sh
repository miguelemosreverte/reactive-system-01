#!/bin/bash
# Benchmark History & Regression Testing
#
# This script manages benchmark history and provides regression comparisons.
# Results are stored indexed by git commit hash.
#
# Usage:
#   ./scripts/benchmark-history.sh save          # Save current results to history
#   ./scripts/benchmark-history.sh compare       # Compare with last commit
#   ./scripts/benchmark-history.sh compare <sha> # Compare with specific commit
#   ./scripts/benchmark-history.sh list          # List all stored benchmarks
#   ./scripts/benchmark-history.sh show <sha>    # Show results for a commit
#   ./scripts/benchmark-history.sh report        # Generate regression report

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPORTS_DIR="$PROJECT_DIR/reports"
HISTORY_DIR="$PROJECT_DIR/.benchmark-history"
VERSIONS_FILE="$PROJECT_DIR/benchmark-history.json"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

print_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
print_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }
print_warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }

# Get current git commit info
get_current_commit() {
    git rev-parse --short HEAD 2>/dev/null || echo "unknown"
}

get_full_commit() {
    git rev-parse HEAD 2>/dev/null || echo "unknown"
}

get_commit_message() {
    local sha="${1:-HEAD}"
    git log -1 --format='%s' "$sha" 2>/dev/null || echo "No message"
}

get_commit_date() {
    local sha="${1:-HEAD}"
    git log -1 --format='%ci' "$sha" 2>/dev/null | cut -d' ' -f1 || echo "Unknown"
}

# Initialize history directory
init_history() {
    mkdir -p "$HISTORY_DIR"
    if [[ ! -f "$VERSIONS_FILE" ]]; then
        echo '{"benchmarks":[]}' > "$VERSIONS_FILE"
    fi
}

# Save current benchmark results to history
save_to_history() {
    init_history

    local commit_sha=$(get_full_commit)
    local commit_short=$(get_current_commit)
    local commit_msg=$(get_commit_message)
    local commit_date=$(get_commit_date)
    local timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    if [[ "$commit_sha" == "unknown" ]]; then
        print_error "Not in a git repository"
        exit 1
    fi

    local commit_dir="$HISTORY_DIR/$commit_short"

    # Check if we have any results
    if [[ ! -d "$REPORTS_DIR" ]]; then
        print_error "No reports directory found. Run benchmarks first."
        exit 1
    fi

    # Create commit directory
    mkdir -p "$commit_dir"

    # Copy all component results
    local components_saved=0
    for component in http kafka flink drools gateway full; do
        local results_file="$REPORTS_DIR/$component/results.json"
        if [[ -f "$results_file" ]]; then
            cp "$results_file" "$commit_dir/${component}.json"
            ((components_saved++))
        fi
    done

    if [[ $components_saved -eq 0 ]]; then
        print_error "No benchmark results found in $REPORTS_DIR"
        rmdir "$commit_dir" 2>/dev/null || true
        exit 1
    fi

    # Create summary file
    python3 << EOF
import json
from pathlib import Path
from datetime import datetime

commit_dir = Path("$commit_dir")
summary = {
    "commit": "$commit_sha",
    "shortCommit": "$commit_short",
    "message": """$commit_msg""",
    "date": "$commit_date",
    "timestamp": "$timestamp",
    "components": {}
}

for component in ["http", "kafka", "flink", "drools", "gateway", "full"]:
    results_file = commit_dir / f"{component}.json"
    if results_file.exists():
        with open(results_file) as f:
            data = json.load(f)
        summary["components"][component] = {
            "peakThroughput": data.get("peakThroughput", 0),
            "avgThroughput": data.get("avgThroughput", 0),
            "latencyP50": data.get("latency", {}).get("p50", 0),
            "latencyP99": data.get("latency", {}).get("p99", 0),
            "totalOperations": data.get("totalOperations", 0),
            "successRate": data.get("successfulOperations", 0) / max(data.get("totalOperations", 1), 1) * 100
        }

with open(commit_dir / "summary.json", "w") as f:
    json.dump(summary, f, indent=2)

# Update versions.json
versions_file = Path("$VERSIONS_FILE")
versions = json.load(open(versions_file))

# Remove existing entry for this commit if any
versions["benchmarks"] = [b for b in versions["benchmarks"] if b["commit"] != "$commit_sha"]

# Add new entry at the beginning
versions["benchmarks"].insert(0, {
    "commit": "$commit_sha",
    "shortCommit": "$commit_short",
    "message": """$commit_msg""",
    "date": "$commit_date",
    "timestamp": "$timestamp",
    "components": list(summary["components"].keys())
})

# Keep only last 50 entries
versions["benchmarks"] = versions["benchmarks"][:50]

with open(versions_file, "w") as f:
    json.dump(versions, f, indent=2)

print(f"Saved {len(summary['components'])} component benchmarks")
EOF

    print_success "Benchmark results saved for commit $commit_short"
    print_info "Message: $commit_msg"
    print_info "Saved $components_saved component results"
}

# List all stored benchmarks
list_history() {
    init_history

    if [[ ! -f "$VERSIONS_FILE" ]]; then
        print_info "No benchmark history found"
        exit 0
    fi

    python3 << 'EOF'
import json
from pathlib import Path

versions_file = Path("$VERSIONS_FILE".replace("$VERSIONS_FILE", ""))
if not versions_file.name:
    versions_file = Path("EOF")
EOF

    python3 << EOF
import json
from pathlib import Path

versions_file = Path("$VERSIONS_FILE")
if not versions_file.exists():
    print("No benchmark history found")
    exit(0)

versions = json.load(open(versions_file))
benchmarks = versions.get("benchmarks", [])

if not benchmarks:
    print("No benchmark history found")
    exit(0)

print(f"\n{'='*80}")
print(f"{'BENCHMARK HISTORY':^80}")
print(f"{'='*80}\n")
print(f"{'Commit':<10} {'Date':<12} {'Components':<20} {'Message':<35}")
print("-" * 80)

for b in benchmarks[:20]:  # Show last 20
    components = ", ".join(b.get("components", []))[:18]
    message = b.get("message", "")[:33]
    print(f"{b['shortCommit']:<10} {b['date']:<12} {components:<20} {message:<35}")

if len(benchmarks) > 20:
    print(f"\n... and {len(benchmarks) - 20} more entries")

print(f"\nTotal stored: {len(benchmarks)} benchmark runs")
EOF
}

# Show results for a specific commit
show_commit() {
    local commit_sha="$1"

    if [[ -z "$commit_sha" ]]; then
        commit_sha=$(get_current_commit)
    fi

    local commit_dir="$HISTORY_DIR/$commit_sha"

    if [[ ! -d "$commit_dir" ]]; then
        # Try to find by partial match
        local matches=$(find "$HISTORY_DIR" -maxdepth 1 -type d -name "${commit_sha}*" 2>/dev/null | head -1)
        if [[ -n "$matches" ]]; then
            commit_dir="$matches"
            commit_sha=$(basename "$commit_dir")
        else
            print_error "No benchmark results found for commit: $commit_sha"
            exit 1
        fi
    fi

    python3 << EOF
import json
from pathlib import Path

commit_dir = Path("$commit_dir")
summary_file = commit_dir / "summary.json"

if not summary_file.exists():
    print("No summary found for this commit")
    exit(1)

summary = json.load(open(summary_file))

print(f"\n{'='*70}")
print(f"Benchmark Results: {summary['shortCommit']}")
print(f"{'='*70}")
print(f"Commit:  {summary['commit']}")
print(f"Message: {summary['message']}")
print(f"Date:    {summary['date']}")
print(f"{'='*70}\n")

print(f"{'Component':<15} {'Peak TPS':<12} {'Avg TPS':<12} {'P50 (ms)':<10} {'P99 (ms)':<10} {'Success %':<10}")
print("-" * 70)

for comp, data in summary["components"].items():
    print(f"{comp:<15} {data['peakThroughput']:<12} {data['avgThroughput']:<12} {data['latencyP50']:<10} {data['latencyP99']:<10} {data['successRate']:.1f}%")

print()
EOF
}

# Format delta with color and arrow
format_delta() {
    local current="$1"
    local previous="$2"
    local metric_type="$3"  # "throughput" or "latency"

    if [[ "$previous" == "0" ]] || [[ -z "$previous" ]]; then
        echo "N/A"
        return
    fi

    python3 << EOF
current = float("$current")
previous = float("$previous")
metric_type = "$metric_type"

if previous == 0:
    print("N/A")
else:
    delta_pct = ((current - previous) / previous) * 100

    # For throughput, higher is better. For latency, lower is better.
    if metric_type == "latency":
        is_improvement = delta_pct < 0
        delta_pct = -delta_pct  # Invert for display (negative latency = improvement)
    else:
        is_improvement = delta_pct > 0

    if abs(delta_pct) < 1:
        print("~0%")
    elif is_improvement:
        print(f"+{abs(delta_pct):.1f}%")
    else:
        print(f"-{abs(delta_pct):.1f}%")
EOF
}

# Compare current results with a previous commit
compare_with() {
    local base_sha="$1"

    # If no base specified, find the previous benchmark
    if [[ -z "$base_sha" ]]; then
        # Find previous commit with benchmarks
        base_sha=$(python3 << EOF
import json
from pathlib import Path

versions_file = Path("$VERSIONS_FILE")
if not versions_file.exists():
    exit(1)

versions = json.load(open(versions_file))
benchmarks = versions.get("benchmarks", [])

current_sha = "$(get_full_commit)"

# Find the first benchmark that's not the current commit
for b in benchmarks:
    if b["commit"] != current_sha:
        print(b["shortCommit"])
        exit(0)

exit(1)
EOF
)
        if [[ -z "$base_sha" ]]; then
            print_warning "No previous benchmark found to compare against"
            exit 0
        fi
    fi

    local current_sha=$(get_current_commit)

    # Find the base commit directory
    local base_dir="$HISTORY_DIR/$base_sha"
    if [[ ! -d "$base_dir" ]]; then
        local matches=$(find "$HISTORY_DIR" -maxdepth 1 -type d -name "${base_sha}*" 2>/dev/null | head -1)
        if [[ -n "$matches" ]]; then
            base_dir="$matches"
            base_sha=$(basename "$base_dir")
        else
            print_error "No benchmark results found for commit: $base_sha"
            exit 1
        fi
    fi

    print_info "Comparing current results with commit $base_sha"
    echo ""

    python3 << EOF
import json
from pathlib import Path

reports_dir = Path("$REPORTS_DIR")
base_dir = Path("$base_dir")
base_summary = base_dir / "summary.json"

if not base_summary.exists():
    print("No summary found for base commit")
    exit(1)

base_data = json.load(open(base_summary))

# ANSI colors
GREEN = '\033[0;32m'
RED = '\033[0;31m'
YELLOW = '\033[1;33m'
CYAN = '\033[0;36m'
BOLD = '\033[1m'
NC = '\033[0m'

def format_delta(current, previous, is_latency=False):
    """Format delta with color. For latency, lower is better."""
    if previous == 0:
        return f"{YELLOW}N/A{NC}"

    delta_pct = ((current - previous) / previous) * 100

    if is_latency:
        # For latency, negative delta is good (faster)
        is_improvement = delta_pct < 0
    else:
        # For throughput, positive delta is good (more ops)
        is_improvement = delta_pct > 0

    if abs(delta_pct) < 1:
        return f"{YELLOW}~0%{NC}"
    elif is_improvement:
        return f"{GREEN}+{abs(delta_pct):.1f}%{NC}"
    else:
        return f"{RED}-{abs(delta_pct):.1f}%{NC}"

print(f"{BOLD}{'='*90}{NC}")
print(f"{BOLD}BENCHMARK REGRESSION COMPARISON{NC}")
print(f"{BOLD}{'='*90}{NC}")
print(f"Current:  {CYAN}$(get_current_commit){NC} ($(get_commit_message | head -c 50))")
print(f"Baseline: {CYAN}$base_sha{NC} ({base_data['message'][:50]})")
print(f"{BOLD}{'='*90}{NC}\n")

# Header
print(f"{BOLD}{'Component':<12} {'Metric':<15} {'Current':<12} {'Baseline':<12} {'Delta':<15}{NC}")
print("-" * 70)

components = ["full", "drools", "kafka", "gateway", "http"]
improvements = 0
regressions = 0

for comp in components:
    base_comp = base_data.get("components", {}).get(comp)
    current_file = reports_dir / comp / "results.json"

    if not base_comp:
        continue

    if not current_file.exists():
        print(f"{comp:<12} {'(no current data)'}")
        continue

    current_data = json.load(open(current_file))
    current_comp = {
        "peakThroughput": current_data.get("peakThroughput", 0),
        "avgThroughput": current_data.get("avgThroughput", 0),
        "latencyP50": current_data.get("latency", {}).get("p50", 0),
        "latencyP99": current_data.get("latency", {}).get("p99", 0)
    }

    # Print component name for first row
    first_row = True

    for metric, is_latency in [("peakThroughput", False), ("avgThroughput", False), ("latencyP99", True)]:
        curr_val = current_comp.get(metric, 0)
        base_val = base_comp.get(metric, 0)
        delta_str = format_delta(curr_val, base_val, is_latency)

        # Track improvements/regressions
        if base_val > 0:
            delta_pct = ((curr_val - base_val) / base_val) * 100
            if is_latency:
                is_improvement = delta_pct < -5
                is_regression = delta_pct > 5
            else:
                is_improvement = delta_pct > 5
                is_regression = delta_pct < -5

            if is_improvement:
                improvements += 1
            elif is_regression:
                regressions += 1

        metric_name = {
            "peakThroughput": "Peak TPS",
            "avgThroughput": "Avg TPS",
            "latencyP99": "P99 Latency"
        }.get(metric, metric)

        unit = "ms" if is_latency else "ops/s"
        comp_name = comp if first_row else ""
        print(f"{comp_name:<12} {metric_name:<15} {curr_val:<12} {base_val:<12} {delta_str}")
        first_row = False

    print()

# Summary
print(f"\n{BOLD}Summary:{NC}")
if improvements > regressions:
    print(f"  {GREEN}Overall: Improved{NC} ({improvements} improvements, {regressions} regressions)")
elif regressions > improvements:
    print(f"  {RED}Overall: Regressed{NC} ({improvements} improvements, {regressions} regressions)")
else:
    print(f"  {YELLOW}Overall: No significant change{NC}")

print()
EOF
}

# Generate a detailed regression report (JSON)
generate_regression_report() {
    init_history

    local current_sha=$(get_current_commit)
    local output_file="$REPORTS_DIR/regression.json"

    python3 << EOF
import json
from pathlib import Path
from datetime import datetime

reports_dir = Path("$REPORTS_DIR")
history_dir = Path("$HISTORY_DIR")
versions_file = Path("$VERSIONS_FILE")

if not versions_file.exists():
    print("No benchmark history found")
    exit(1)

versions = json.load(open(versions_file))
benchmarks = versions.get("benchmarks", [])

# Get current results
current_results = {}
for comp in ["http", "kafka", "flink", "drools", "gateway", "full"]:
    results_file = reports_dir / comp / "results.json"
    if results_file.exists():
        data = json.load(open(results_file))
        current_results[comp] = {
            "peakThroughput": data.get("peakThroughput", 0),
            "avgThroughput": data.get("avgThroughput", 0),
            "latencyP50": data.get("latency", {}).get("p50", 0),
            "latencyP99": data.get("latency", {}).get("p99", 0),
            "totalOperations": data.get("totalOperations", 0),
            "successfulOperations": data.get("successfulOperations", 0)
        }

# Build comparison with historical data
history = []
for b in benchmarks[:10]:  # Last 10 runs
    commit_dir = history_dir / b["shortCommit"]
    summary_file = commit_dir / "summary.json"

    if summary_file.exists():
        summary = json.load(open(summary_file))
        history.append({
            "commit": b["shortCommit"],
            "fullCommit": b["commit"],
            "message": b["message"],
            "date": b["date"],
            "timestamp": b["timestamp"],
            "components": summary.get("components", {})
        })

# Calculate deltas compared to most recent historical entry
deltas = {}
if history:
    baseline = history[0]["components"]
    for comp, current in current_results.items():
        base = baseline.get(comp, {})
        if base:
            deltas[comp] = {
                "peakThroughput": {
                    "current": current["peakThroughput"],
                    "baseline": base.get("peakThroughput", 0),
                    "delta": ((current["peakThroughput"] - base.get("peakThroughput", 0)) / max(base.get("peakThroughput", 1), 1)) * 100
                },
                "avgThroughput": {
                    "current": current["avgThroughput"],
                    "baseline": base.get("avgThroughput", 0),
                    "delta": ((current["avgThroughput"] - base.get("avgThroughput", 0)) / max(base.get("avgThroughput", 1), 1)) * 100
                },
                "latencyP99": {
                    "current": current["latencyP99"],
                    "baseline": base.get("latencyP99", 0),
                    "delta": ((current["latencyP99"] - base.get("latencyP99", 0)) / max(base.get("latencyP99", 1), 1)) * 100
                }
            }

report = {
    "generatedAt": datetime.utcnow().isoformat() + "Z",
    "currentCommit": "$current_sha",
    "currentResults": current_results,
    "baselineCommit": history[0]["commit"] if history else None,
    "deltas": deltas,
    "history": history
}

output_file = reports_dir / "regression.json"
with open(output_file, "w") as f:
    json.dump(report, f, indent=2)

print(f"Regression report generated: {output_file}")
EOF

    # Update the dashboard to include regression data
    update_dashboard_with_regression

    print_success "Regression report generated: $output_file"
}

# Update dashboard index with regression data
update_dashboard_with_regression() {
    local regression_file="$REPORTS_DIR/regression.json"

    if [[ ! -f "$regression_file" ]]; then
        return
    fi

    python3 << EOF
import json
from pathlib import Path

reports_dir = Path("$REPORTS_DIR")
index_file = reports_dir / "index.html"
regression_file = reports_dir / "regression.json"

if not index_file.exists() or not regression_file.exists():
    exit(0)

# Read existing index
with open(index_file) as f:
    html = f.read()

# Read regression data
with open(regression_file) as f:
    regression = json.load(f)

# Inject regression data into the page
injection_point = "window.__BENCHMARK_INDEX__"
if injection_point in html:
    # Add regression data after the index data
    regression_script = f"\n        window.__BENCHMARK_REGRESSION__ = {json.dumps(regression, indent=2)};"

    # Find the end of the index data script
    idx = html.find(injection_point)
    end_idx = html.find("</script>", idx)

    # Insert regression data before the closing script tag
    html = html[:end_idx] + regression_script + html[end_idx:]

    # Also ensure regression overlay script is included
    overlay_script = '<script src="assets/regression-overlay.js"></script>'
    if overlay_script not in html and 'benchmark-index.js' in html:
        html = html.replace(
            '<script src="assets/benchmark-index.js"></script>',
            '<script src="assets/benchmark-index.js"></script>\n    ' + overlay_script
        )

    with open(index_file, "w") as f:
        f.write(html)

print("Dashboard updated with regression data")
EOF
}

# Main
case "${1:-help}" in
    save)
        save_to_history
        ;;
    compare)
        compare_with "$2"
        ;;
    list)
        list_history
        ;;
    show)
        show_commit "$2"
        ;;
    report)
        generate_regression_report
        ;;
    help|--help|-h)
        echo ""
        echo "Benchmark History & Regression Testing"
        echo "======================================="
        echo ""
        echo "Usage: ./scripts/benchmark-history.sh <command> [args]"
        echo ""
        echo "Commands:"
        echo "  save              Save current benchmark results to history"
        echo "  compare [sha]     Compare current results with baseline"
        echo "  list              List all stored benchmark runs"
        echo "  show <sha>        Show results for a specific commit"
        echo "  report            Generate regression JSON report"
        echo ""
        echo "Examples:"
        echo "  ./scripts/benchmark-history.sh save"
        echo "  ./scripts/benchmark-history.sh compare"
        echo "  ./scripts/benchmark-history.sh compare 58b5598"
        echo "  ./scripts/benchmark-history.sh list"
        echo ""
        ;;
    *)
        print_error "Unknown command: $1"
        "$0" help
        exit 1
        ;;
esac
