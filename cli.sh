#!/bin/bash
# Reactive System CLI
# Entry point that delegates to Go CLI
#
# Usage: ./cli.sh <command> [args]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_DIR="$SCRIPT_DIR/platform/cli"
REPORTS_DIR="$SCRIPT_DIR/reports"
ASSETS_SRC="$SCRIPT_DIR/platform/reports/assets"

# Colima recommended resources (based on system capacity)
COLIMA_MIN_CPU=6
COLIMA_MIN_MEM=12  # GB

# Set Docker host for Colima if running
if [[ -S "$HOME/.colima/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.colima/docker.sock"
fi

# ============================================================================
# Colima Resource Management
# ============================================================================

# Check if Colima is the Docker runtime
is_colima() {
    # Check if using Colima socket directly or via Docker context
    [[ "$DOCKER_HOST" == *"colima"* ]] || [[ "$(docker context show 2>/dev/null)" == "colima" ]]
}

# Get current Colima resources
get_colima_resources() {
    if [[ -f "$HOME/.colima/default/colima.yaml" ]]; then
        local cpu=$(grep "^cpu:" "$HOME/.colima/default/colima.yaml" | awk '{print $2}')
        local mem=$(grep "^memory:" "$HOME/.colima/default/colima.yaml" | awk '{print $2}')
        echo "$cpu $mem"
    else
        echo "0 0"
    fi
}

# Get system resources
get_system_resources() {
    local cpu=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo "4")
    local mem_bytes=$(sysctl -n hw.memsize 2>/dev/null || grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2*1024}' || echo "8589934592")
    local mem_gb=$((mem_bytes / 1073741824))
    echo "$cpu $mem_gb"
}

# Calculate recommended Colima resources
get_recommended_resources() {
    read sys_cpu sys_mem <<< $(get_system_resources)

    # Allocate 2/3 of CPUs (min 4, leave at least 2 for host)
    local rec_cpu=$((sys_cpu * 2 / 3))
    [[ $rec_cpu -lt 4 ]] && rec_cpu=4
    [[ $rec_cpu -gt $((sys_cpu - 2)) ]] && rec_cpu=$((sys_cpu - 2))

    # Allocate 2/3 of memory (min 8GB, leave at least 4GB for host)
    local rec_mem=$((sys_mem * 2 / 3))
    [[ $rec_mem -lt 8 ]] && rec_mem=8
    [[ $rec_mem -gt $((sys_mem - 4)) ]] && rec_mem=$((sys_mem - 4))

    echo "$rec_cpu $rec_mem"
}

# Check Colima resources and warn if insufficient
check_colima_resources() {
    if ! is_colima; then
        return 0
    fi

    read cur_cpu cur_mem <<< $(get_colima_resources)
    read rec_cpu rec_mem <<< $(get_recommended_resources)
    read sys_cpu sys_mem <<< $(get_system_resources)

    if [[ $cur_cpu -lt $COLIMA_MIN_CPU ]] || [[ $cur_mem -lt $COLIMA_MIN_MEM ]]; then
        echo ""
        echo "⚠️  Colima Resource Warning"
        echo "   Current:     ${cur_cpu} CPUs, ${cur_mem} GB RAM"
        echo "   Recommended: ${rec_cpu} CPUs, ${rec_mem} GB RAM"
        echo "   System:      ${sys_cpu} CPUs, ${sys_mem} GB RAM"
        echo ""
        echo "   To optimize: ./cli.sh setup-colima"
        echo ""
        return 1
    fi
    return 0
}

# Setup Colima with optimal resources
setup_colima() {
    echo "=== Colima Resource Configuration ==="
    echo ""

    read sys_cpu sys_mem <<< $(get_system_resources)
    read rec_cpu rec_mem <<< $(get_recommended_resources)
    read cur_cpu cur_mem <<< $(get_colima_resources)

    echo "System Resources:"
    echo "  CPUs:   $sys_cpu"
    echo "  Memory: ${sys_mem} GB"
    echo ""
    echo "Current Colima:"
    echo "  CPUs:   $cur_cpu"
    echo "  Memory: ${cur_mem} GB"
    echo ""
    echo "Recommended for Reactive System:"
    echo "  CPUs:   $rec_cpu (leaving $((sys_cpu - rec_cpu)) for host)"
    echo "  Memory: ${rec_mem} GB (leaving $((sys_mem - rec_mem)) GB for host)"
    echo ""

    if [[ $cur_cpu -ge $rec_cpu ]] && [[ $cur_mem -ge $rec_mem ]]; then
        echo "✓ Colima already has sufficient resources"
        return 0
    fi

    echo "To apply recommended settings, run:"
    echo ""
    echo "  colima stop"
    echo "  colima start --cpu $rec_cpu --memory $rec_mem --vm-type vz --mount-type virtiofs"
    echo ""

    read -p "Apply now? (y/N) " -n 1 -r
    echo ""

    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo ""
        echo "Stopping Colima..."
        colima stop 2>/dev/null || /opt/homebrew/bin/colima stop 2>/dev/null || true

        echo "Starting Colima with optimized resources..."
        colima start --cpu "$rec_cpu" --memory "$rec_mem" --vm-type vz --mount-type virtiofs 2>/dev/null || \
        /opt/homebrew/bin/colima start --cpu "$rec_cpu" --memory "$rec_mem" --vm-type vz --mount-type virtiofs

        echo ""
        echo "✓ Colima restarted with $rec_cpu CPUs and ${rec_mem} GB RAM"
        echo ""
        echo "Verify with: docker info | grep -E '(CPUs|Memory)'"
    fi
}

# Function to copy benchmark assets
copy_benchmark_assets() {
    if [[ -d "$ASSETS_SRC" ]]; then
        local ASSETS_DST="$REPORTS_DIR/assets"
        mkdir -p "$ASSETS_DST"
        cp -r "$ASSETS_SRC"/* "$ASSETS_DST/" 2>/dev/null || true
        echo "→ Benchmark assets copied to $ASSETS_DST"
    fi
}

# Function to run CLI and handle post-processing
run_cli() {
    local exit_code=0

    # Check if Go is available
    if command -v go &>/dev/null; then
        # Use go run for always-up-to-date execution
        cd "$CLI_DIR"
        go run . "$@" || exit_code=$?
    elif [[ -x "$CLI_DIR/reactive" ]]; then
        # Fallback: try to use pre-built binary
        "$CLI_DIR/reactive" "$@" || exit_code=$?
    else
        # No Go, no binary - show instructions
        echo ""
        echo "Reactive System CLI"
        echo "==================="
        echo ""
        echo "Go is not installed. Please install Go to use the CLI:"
        echo ""
        echo "  macOS:   brew install go"
        echo "  Linux:   sudo apt install golang-go"
        echo "  Windows: https://go.dev/dl/"
        echo ""
        echo "Or build the CLI binary once:"
        echo ""
        echo "  cd cli && go build -o reactive . && cd .."
        echo ""
        echo "Then run: ./cli.sh <command>"
        echo ""
        exit 1
    fi

    # Post-processing for bench commands: copy assets
    if [[ "$1" == "bench" && $exit_code -eq 0 ]]; then
        copy_benchmark_assets
    fi

    return $exit_code
}

# Handle CLI-level commands before delegating to Go CLI
case "${1:-}" in
    setup-colima)
        setup_colima
        exit $?
        ;;
    check-colima)
        check_colima_resources
        exit $?
        ;;
    start)
        # Warn about Colima resources before starting
        check_colima_resources || true
        run_cli "$@"
        ;;
    *)
        run_cli "$@"
        ;;
esac
