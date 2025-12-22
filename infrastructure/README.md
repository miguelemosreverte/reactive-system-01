# Reactive System Infrastructure

This directory contains all infrastructure-as-code for deploying the Reactive System to:
- **Local development** using Kind (Kubernetes in Docker)
- **AWS** using EKS (Elastic Kubernetes Service)
- **GCP** using GKE (Google Kubernetes Engine)

## Quick Start

### Local Development (Kind)

```bash
# Deploy to local kind cluster
./scripts/local-cluster.sh up

# Access services
# UI:           http://localhost:30000
# Gateway API:  http://localhost:30080
# Grafana:      http://localhost:30001
# Jaeger:       http://localhost:30686
# Flink:        http://localhost:30081

# Delete cluster when done
./scripts/local-cluster.sh down
```

### AWS EKS

```bash
cd terraform/environments/aws
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your settings

terraform init
terraform apply

# Configure kubectl
aws eks update-kubeconfig --region us-west-2 --name reactive-system
```

### GCP GKE

```bash
cd terraform/environments/gcp
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your project ID

terraform init
terraform apply

# Configure kubectl
gcloud container clusters get-credentials reactive-system --region us-central1
```

## Directory Structure

```
infrastructure/
├── k8s/                          # Kubernetes manifests
│   ├── base/                     # Base manifests (Kustomize)
│   │   ├── namespaces/           # Namespace definitions
│   │   ├── kafka/                # Kafka + Zookeeper StatefulSets
│   │   ├── flink/                # Flink JobManager + TaskManager
│   │   ├── application/          # Gateway, Drools, UI, Benchmark
│   │   └── observability/        # Jaeger, Loki, Prometheus, Grafana
│   ├── overlays/
│   │   ├── local/                # Local development overrides
│   │   └── production/           # Production overrides
│   └── local/
│       └── kind-config.yaml      # Kind cluster configuration
│
├── terraform/                    # Terraform modules
│   ├── modules/
│   │   ├── aws-eks/              # AWS EKS module
│   │   └── gcp-gke/              # GCP GKE module
│   └── environments/
│       ├── aws/                  # AWS environment
│       └── gcp/                  # GCP environment
│
└── scripts/                      # Deployment scripts
    ├── local-cluster.sh          # Kind cluster management
    └── deploy.sh                 # Unified deployment script
```

## Kubernetes Architecture

### Namespaces

| Namespace | Purpose |
|-----------|---------|
| `reactive-infra` | Kafka, Zookeeper |
| `reactive-observability` | Jaeger, Loki, Prometheus, Grafana, OTEL Collector |
| `reactive-app` | Gateway, Drools, Flink, UI, Benchmark |

### Services

| Service | Namespace | Ports | Description |
|---------|-----------|-------|-------------|
| kafka | reactive-infra | 9092, 29092 | Apache Kafka |
| zookeeper | reactive-infra | 2181 | ZooKeeper |
| flink-jobmanager | reactive-app | 8081 | Flink Job Manager |
| flink-taskmanager | reactive-app | 9249 | Flink Task Manager (metrics) |
| gateway | reactive-app | 3000 | Counter Application API |
| drools | reactive-app | 8080 | Business Rules Engine |
| ui | reactive-app | 80 | React Frontend |
| benchmark | reactive-app | 8090 | Benchmark Service |
| jaeger | reactive-observability | 16686 | Distributed Tracing UI |
| prometheus | reactive-observability | 9090 | Metrics |
| grafana | reactive-observability | 3000 | Dashboards |
| loki | reactive-observability | 3100 | Log Aggregation |
| otel-collector | reactive-observability | 4317, 4318 | OpenTelemetry Collector |

## Deployment Scripts

### local-cluster.sh

```bash
./scripts/local-cluster.sh up           # Create cluster and deploy
./scripts/local-cluster.sh down         # Delete cluster
./scripts/local-cluster.sh status       # Show cluster status
./scripts/local-cluster.sh load-images  # Rebuild and load images
./scripts/local-cluster.sh apply        # Re-apply manifests
```

### deploy.sh

```bash
./scripts/deploy.sh local               # Deploy to kind
./scripts/deploy.sh aws -e production   # Deploy to AWS
./scripts/deploy.sh gcp -e staging      # Deploy to GCP
./scripts/deploy.sh apply --dry-run     # Preview changes
./scripts/deploy.sh status              # Show status
```

## Terraform Configuration

### AWS EKS Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `cluster_name` | reactive-system | EKS cluster name |
| `cluster_version` | 1.28 | Kubernetes version |
| `region` | us-west-2 | AWS region |
| `node_instance_types` | ["t3.large"] | EC2 instance types |
| `node_desired_size` | 3 | Desired node count |

### GCP GKE Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `project_id` | (required) | GCP project ID |
| `cluster_name` | reactive-system | GKE cluster name |
| `region` | us-central1 | GCP region |
| `node_machine_type` | e2-standard-4 | Machine type |
| `node_count` | 1 | Nodes per zone |

## Node Pools

Both AWS and GCP configurations include two node pools:

1. **General**: For general workloads (Gateway, UI, Drools)
2. **Streaming**: High-memory nodes for Flink and Kafka (with taints)

## Resource Requirements

### Minimum (Local/Dev)
- 4 CPU cores
- 8GB RAM
- 20GB disk space

### Production
- 8+ CPU cores per node
- 16GB+ RAM per node
- 100GB+ SSD storage

## Troubleshooting

### Kind cluster issues

```bash
# Check cluster status
kind get clusters
kubectl cluster-info --context kind-reactive-system

# View pod logs
kubectl logs -f <pod-name> -n <namespace>

# Restart a deployment
kubectl rollout restart deployment/<name> -n <namespace>
```

### Image loading issues

```bash
# Manually load an image
docker build -t reactive-system-01-application ./application
kind load docker-image reactive-system-01-application --name reactive-system
```

### Terraform issues

```bash
# Reset terraform state (careful!)
rm -rf .terraform terraform.tfstate*
terraform init

# View current state
terraform show

# Destroy infrastructure
terraform destroy
```
