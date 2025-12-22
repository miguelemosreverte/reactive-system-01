# AWS EKS Environment Configuration

terraform {
  required_version = ">= 1.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = ">= 2.0"
    }
  }

  # Uncomment and configure for remote state
  # backend "s3" {
  #   bucket         = "reactive-system-terraform-state"
  #   key            = "eks/terraform.tfstate"
  #   region         = "us-west-2"
  #   encrypt        = true
  #   dynamodb_table = "reactive-system-terraform-locks"
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "reactive-system"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# EKS Cluster
module "eks_cluster" {
  source = "../../modules/aws-eks"

  cluster_name    = var.cluster_name
  cluster_version = var.cluster_version
  region          = var.region

  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones

  node_instance_types = var.node_instance_types
  node_desired_size   = var.node_desired_size
  node_min_size       = var.node_min_size
  node_max_size       = var.node_max_size

  environment = var.environment
  tags        = var.tags

  enable_cluster_autoscaler = var.enable_cluster_autoscaler
  enable_metrics_server     = var.enable_metrics_server
}

# Configure Kubernetes provider
provider "kubernetes" {
  host                   = module.eks_cluster.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks_cluster.cluster_certificate_authority_data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", module.eks_cluster.cluster_name, "--region", var.region]
  }
}

# Configure Helm provider
provider "helm" {
  kubernetes {
    host                   = module.eks_cluster.cluster_endpoint
    cluster_ca_certificate = base64decode(module.eks_cluster.cluster_certificate_authority_data)

    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", module.eks_cluster.cluster_name, "--region", var.region]
    }
  }
}

# Deploy reactive-system namespaces
resource "kubernetes_namespace" "reactive_infra" {
  metadata {
    name = "reactive-infra"
    labels = {
      "app.kubernetes.io/part-of" = "reactive-system"
    }
  }

  depends_on = [module.eks_cluster]
}

resource "kubernetes_namespace" "reactive_observability" {
  metadata {
    name = "reactive-observability"
    labels = {
      "app.kubernetes.io/part-of" = "reactive-system"
    }
  }

  depends_on = [module.eks_cluster]
}

resource "kubernetes_namespace" "reactive_app" {
  metadata {
    name = "reactive-app"
    labels = {
      "app.kubernetes.io/part-of" = "reactive-system"
    }
  }

  depends_on = [module.eks_cluster]
}

# Install metrics-server if enabled
resource "helm_release" "metrics_server" {
  count = var.enable_metrics_server ? 1 : 0

  name       = "metrics-server"
  repository = "https://kubernetes-sigs.github.io/metrics-server/"
  chart      = "metrics-server"
  namespace  = "kube-system"
  version    = "3.11.0"

  set {
    name  = "args[0]"
    value = "--kubelet-insecure-tls"
  }

  depends_on = [module.eks_cluster]
}

# Install cluster-autoscaler if enabled
resource "helm_release" "cluster_autoscaler" {
  count = var.enable_cluster_autoscaler ? 1 : 0

  name       = "cluster-autoscaler"
  repository = "https://kubernetes.github.io/autoscaler"
  chart      = "cluster-autoscaler"
  namespace  = "kube-system"
  version    = "9.34.0"

  set {
    name  = "autoDiscovery.clusterName"
    value = module.eks_cluster.cluster_name
  }

  set {
    name  = "awsRegion"
    value = var.region
  }

  set {
    name  = "rbac.serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
    value = module.eks_cluster.cluster_autoscaler_role_arn
  }

  depends_on = [module.eks_cluster]
}
