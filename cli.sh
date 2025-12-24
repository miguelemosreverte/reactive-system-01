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

# Set Docker host for Colima if running
if [[ -S "$HOME/.colima/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.colima/docker.sock"
fi

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

run_cli "$@"
