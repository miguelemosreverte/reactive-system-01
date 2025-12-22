#!/bin/bash
# Reactive System - Local Kubernetes Cluster Setup
# This script sets up a local Kubernetes cluster using kind

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"
CLUSTER_NAME="reactive-system"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check Docker
    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed. Please install Docker first."
        exit 1
    fi

    if ! docker info &> /dev/null; then
        log_error "Docker daemon is not running. Please start Docker."
        exit 1
    fi

    # Check/Install kind
    if ! command -v kind &> /dev/null; then
        log_warn "kind is not installed. Installing..."
        if [[ "$OSTYPE" == "darwin"* ]]; then
            brew install kind
        elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
            curl -Lo ./kind https://kind.sigs.k8s.io/dl/v0.20.0/kind-linux-amd64
            chmod +x ./kind
            sudo mv ./kind /usr/local/bin/kind
        fi
    fi

    # Check/Install kubectl
    if ! command -v kubectl &> /dev/null; then
        log_warn "kubectl is not installed. Installing..."
        if [[ "$OSTYPE" == "darwin"* ]]; then
            brew install kubectl
        elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
            curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
            chmod +x kubectl
            sudo mv kubectl /usr/local/bin/
        fi
    fi

    # Check/Install Helm
    if ! command -v helm &> /dev/null; then
        log_warn "Helm is not installed. Installing..."
        if [[ "$OSTYPE" == "darwin"* ]]; then
            brew install helm
        elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
            curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
        fi
    fi

    log_success "All prerequisites are installed"
}

# Create the kind cluster
create_cluster() {
    log_info "Creating kind cluster '$CLUSTER_NAME'..."

    # Check if cluster already exists
    if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
        log_warn "Cluster '$CLUSTER_NAME' already exists"
        read -p "Do you want to delete and recreate it? (y/N): " confirm
        if [[ "$confirm" =~ ^[Yy]$ ]]; then
            kind delete cluster --name "$CLUSTER_NAME"
        else
            log_info "Using existing cluster"
            return 0
        fi
    fi

    # Create the cluster
    kind create cluster --config "$INFRA_DIR/k8s/local/kind-config.yaml"

    # Wait for cluster to be ready
    log_info "Waiting for cluster to be ready..."
    kubectl wait --for=condition=Ready nodes --all --timeout=120s

    log_success "Cluster '$CLUSTER_NAME' created successfully"
}

# Install NGINX Ingress Controller
install_ingress() {
    log_info "Installing NGINX Ingress Controller..."

    kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

    # Wait for ingress to be ready
    log_info "Waiting for ingress controller to be ready..."
    kubectl wait --namespace ingress-nginx \
        --for=condition=ready pod \
        --selector=app.kubernetes.io/component=controller \
        --timeout=120s

    log_success "Ingress controller installed"
}

# Load Docker images into kind
load_images() {
    log_info "Building and loading Docker images into kind..."

    # Build images
    docker compose -f "$ROOT_DIR/docker-compose.yml" build

    # Get list of images to load
    IMAGES=(
        "reactive-system-01-application"
        "reactive-system-01-drools"
        "reactive-system-01-flink"
        "reactive-system-01-ui"
        "reactive-system-01-benchmark"
    )

    for image in "${IMAGES[@]}"; do
        if docker image inspect "$image" &>/dev/null; then
            log_info "Loading image: $image"
            kind load docker-image "$image" --name "$CLUSTER_NAME"
        fi
    done

    log_success "Docker images loaded into kind"
}

# Apply Kubernetes manifests
apply_manifests() {
    log_info "Applying Kubernetes manifests..."

    # Apply base manifests
    kubectl apply -k "$INFRA_DIR/k8s/overlays/local"

    log_success "Kubernetes manifests applied"
}

# Wait for all pods to be ready
wait_for_pods() {
    log_info "Waiting for all pods to be ready..."

    # Wait for pods in each namespace
    for ns in reactive-infra reactive-observability reactive-app; do
        if kubectl get namespace "$ns" &>/dev/null; then
            log_info "Waiting for pods in namespace: $ns"
            kubectl wait --namespace "$ns" \
                --for=condition=ready pod \
                --all \
                --timeout=300s || true
        fi
    done

    log_success "All pods are ready"
}

# Print access information
print_access_info() {
    echo ""
    echo "============================================"
    echo "  Reactive System - Local Kubernetes"
    echo "============================================"
    echo ""
    echo "Access URLs (NodePort):"
    echo "  - UI:           http://localhost:30000"
    echo "  - Gateway API:  http://localhost:30080"
    echo "  - Grafana:      http://localhost:30001"
    echo "  - Prometheus:   http://localhost:30090"
    echo "  - Jaeger:       http://localhost:30686"
    echo "  - Flink:        http://localhost:30081"
    echo ""
    echo "Useful commands:"
    echo "  kubectl get pods -A              # List all pods"
    echo "  kubectl logs -f <pod> -n <ns>    # View pod logs"
    echo "  kind delete cluster --name $CLUSTER_NAME  # Delete cluster"
    echo ""
}

# Main function
main() {
    local command="${1:-up}"

    case "$command" in
        up|start|create)
            check_prerequisites
            create_cluster
            install_ingress
            load_images
            apply_manifests
            wait_for_pods
            print_access_info
            ;;
        down|stop|delete)
            log_info "Deleting cluster '$CLUSTER_NAME'..."
            kind delete cluster --name "$CLUSTER_NAME"
            log_success "Cluster deleted"
            ;;
        status)
            if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
                log_success "Cluster '$CLUSTER_NAME' is running"
                kubectl get nodes
                echo ""
                kubectl get pods -A
            else
                log_warn "Cluster '$CLUSTER_NAME' is not running"
            fi
            ;;
        load-images)
            load_images
            ;;
        apply)
            apply_manifests
            wait_for_pods
            ;;
        *)
            echo "Usage: $0 {up|down|status|load-images|apply}"
            echo ""
            echo "Commands:"
            echo "  up          Create cluster and deploy all services"
            echo "  down        Delete the cluster"
            echo "  status      Show cluster status"
            echo "  load-images Build and load Docker images"
            echo "  apply       Apply Kubernetes manifests"
            exit 1
            ;;
    esac
}

main "$@"
