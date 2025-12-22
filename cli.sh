#!/bin/bash
# Reactive System CLI
# Entry point that delegates to Go CLI
#
# Usage: ./cli.sh <command> [args]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLI_DIR="$SCRIPT_DIR/cli"

# Set Docker host for Colima if running
if [[ -S "$HOME/.colima/docker.sock" ]]; then
    export DOCKER_HOST="unix://$HOME/.colima/docker.sock"
fi

# Check if Go is available
if command -v go &>/dev/null; then
    # Use go run for always-up-to-date execution
    cd "$CLI_DIR"
    exec go run . "$@"
else
    # Fallback: try to use pre-built binary
    if [[ -x "$CLI_DIR/reactive" ]]; then
        exec "$CLI_DIR/reactive" "$@"
    fi

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
