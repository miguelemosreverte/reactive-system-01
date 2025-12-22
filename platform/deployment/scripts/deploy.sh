#!/bin/bash
# Reactive System - Deployment Script
# Unified deployment to local (kind), AWS (EKS), or GCP (GKE)

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(dirname "$SCRIPT_DIR")"
ROOT_DIR="$(dirname "$INFRA_DIR")"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_header() { echo -e "\n${CYAN}=== $1 ===${NC}\n"; }

# Display help
show_help() {
    cat << EOF
Reactive System - Deployment Script

Usage: $0 <command> [options]

Commands:
  local       Deploy to local kind cluster
  aws         Deploy to AWS EKS
  gcp         Deploy to GCP GKE
  apply       Apply Kubernetes manifests to current context
  status      Show deployment status

Options:
  -e, --environment    Environment (local, staging, production)
  -n, --namespace      Target namespace
  --dry-run            Preview changes without applying
  -h, --help           Show this help message

Examples:
  $0 local                          # Deploy to local kind cluster
  $0 aws -e production              # Deploy to AWS EKS production
  $0 gcp -e staging                 # Deploy to GCP GKE staging
  $0 apply --dry-run                # Preview manifests
EOF
}

# Check if kubectl is configured
check_kubectl() {
    if ! kubectl cluster-info &>/dev/null; then
        log_error "kubectl is not configured or cluster is not accessible"
        exit 1
    fi
}

# Build Docker images
build_images() {
    log_header "Building Docker Images"

    cd "$ROOT_DIR"
    docker compose build

    log_success "Docker images built successfully"
}

# Deploy to local kind cluster
deploy_local() {
    log_header "Deploying to Local Kind Cluster"

    # Use the local-cluster.sh script
    "$SCRIPT_DIR/local-cluster.sh" up
}

# Deploy to AWS EKS
deploy_aws() {
    local environment="${1:-production}"

    log_header "Deploying to AWS EKS ($environment)"

    cd "$INFRA_DIR/terraform/environments/aws"

    # Check if terraform is initialized
    if [ ! -d ".terraform" ]; then
        log_info "Initializing Terraform..."
        terraform init
    fi

    # Plan and apply
    log_info "Planning Terraform changes..."
    terraform plan -var="environment=$environment" -out=tfplan

    read -p "Apply these changes? (y/N): " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
        terraform apply tfplan

        # Get kubectl config
        log_info "Configuring kubectl..."
        eval $(terraform output -raw configure_kubectl)

        # Apply Kubernetes manifests
        apply_manifests "$environment"
    fi
}

# Deploy to GCP GKE
deploy_gcp() {
    local environment="${1:-production}"

    log_header "Deploying to GCP GKE ($environment)"

    cd "$INFRA_DIR/terraform/environments/gcp"

    # Check if terraform.tfvars exists
    if [ ! -f "terraform.tfvars" ]; then
        log_error "terraform.tfvars not found. Copy terraform.tfvars.example and configure."
        exit 1
    fi

    # Check if terraform is initialized
    if [ ! -d ".terraform" ]; then
        log_info "Initializing Terraform..."
        terraform init
    fi

    # Plan and apply
    log_info "Planning Terraform changes..."
    terraform plan -var="environment=$environment" -out=tfplan

    read -p "Apply these changes? (y/N): " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
        terraform apply tfplan

        # Get kubectl config
        log_info "Configuring kubectl..."
        eval $(terraform output -raw configure_kubectl)

        # Apply Kubernetes manifests
        apply_manifests "$environment"
    fi
}

# Apply Kubernetes manifests
apply_manifests() {
    local environment="${1:-local}"
    local dry_run="${2:-false}"

    log_header "Applying Kubernetes Manifests ($environment)"

    check_kubectl

    local overlay_path="$INFRA_DIR/k8s/overlays/$environment"

    if [ ! -d "$overlay_path" ]; then
        # Fall back to local if environment overlay doesn't exist
        if [ "$environment" != "local" ]; then
            log_warn "Overlay '$environment' not found, using 'production'"
            overlay_path="$INFRA_DIR/k8s/overlays/production"
        else
            overlay_path="$INFRA_DIR/k8s/overlays/local"
        fi
    fi

    if [ "$dry_run" = "true" ]; then
        log_info "Dry run - showing what would be applied:"
        kubectl apply -k "$overlay_path" --dry-run=client
    else
        kubectl apply -k "$overlay_path"

        log_info "Waiting for deployments to be ready..."

        # Wait for pods in each namespace
        for ns in reactive-infra reactive-observability reactive-app; do
            if kubectl get namespace "$ns" &>/dev/null; then
                log_info "Waiting for pods in namespace: $ns"
                kubectl wait --namespace "$ns" \
                    --for=condition=ready pod \
                    --all \
                    --timeout=300s 2>/dev/null || true
            fi
        done

        log_success "Manifests applied successfully"
    fi
}

# Show deployment status
show_status() {
    log_header "Deployment Status"

    check_kubectl

    echo "Cluster Info:"
    kubectl cluster-info
    echo ""

    echo "Nodes:"
    kubectl get nodes
    echo ""

    echo "Pods by Namespace:"
    for ns in reactive-infra reactive-observability reactive-app; do
        if kubectl get namespace "$ns" &>/dev/null; then
            echo ""
            echo "--- $ns ---"
            kubectl get pods -n "$ns" -o wide
        fi
    done

    echo ""
    echo "Services:"
    kubectl get svc -A | grep -E "(reactive-|NodePort|LoadBalancer)"
}

# Push images to registry (for cloud deployments)
push_images() {
    local registry="${1:-}"

    if [ -z "$registry" ]; then
        log_error "Registry URL required"
        exit 1
    fi

    log_header "Pushing Images to $registry"

    build_images

    local images=(
        "reactive-system-01-application"
        "reactive-system-01-drools"
        "reactive-system-01-flink"
        "reactive-system-01-ui"
        "reactive-system-01-benchmark"
    )

    for image in "${images[@]}"; do
        local remote="$registry/$image:latest"
        log_info "Tagging and pushing $image -> $remote"
        docker tag "$image:latest" "$remote"
        docker push "$remote"
    done

    log_success "Images pushed to registry"
}

# Main
main() {
    local command="${1:-help}"
    shift || true

    local environment="local"
    local dry_run="false"

    # Parse options
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -e|--environment)
                environment="$2"
                shift 2
                ;;
            --dry-run)
                dry_run="true"
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            *)
                shift
                ;;
        esac
    done

    case "$command" in
        local)
            deploy_local
            ;;
        aws)
            deploy_aws "$environment"
            ;;
        gcp)
            deploy_gcp "$environment"
            ;;
        apply)
            apply_manifests "$environment" "$dry_run"
            ;;
        status)
            show_status
            ;;
        push)
            push_images "$environment"
            ;;
        build)
            build_images
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            log_error "Unknown command: $command"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
