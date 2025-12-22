# GCP GKE Environment Configuration

terraform {
  required_version = ">= 1.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
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
  # backend "gcs" {
  #   bucket = "reactive-system-terraform-state"
  #   prefix = "gke/terraform.tfstate"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
}

# GKE Cluster
module "gke_cluster" {
  source = "../../modules/gcp-gke"

  project_id   = var.project_id
  cluster_name = var.cluster_name
  region       = var.region
  zones        = var.zones

  network_name  = var.network_name
  subnet_cidr   = var.subnet_cidr
  pods_cidr     = var.pods_cidr
  services_cidr = var.services_cidr

  kubernetes_version = var.kubernetes_version
  node_machine_type  = var.node_machine_type
  node_count         = var.node_count
  node_min_count     = var.node_min_count
  node_max_count     = var.node_max_count

  environment = var.environment

  enable_horizontal_pod_autoscaler = var.enable_horizontal_pod_autoscaler
  enable_network_policy            = var.enable_network_policy
}

# Get cluster credentials
data "google_client_config" "default" {}

# Configure Kubernetes provider
provider "kubernetes" {
  host                   = "https://${module.gke_cluster.cluster_endpoint}"
  token                  = data.google_client_config.default.access_token
  cluster_ca_certificate = base64decode(module.gke_cluster.cluster_ca_certificate)
}

# Configure Helm provider
provider "helm" {
  kubernetes {
    host                   = "https://${module.gke_cluster.cluster_endpoint}"
    token                  = data.google_client_config.default.access_token
    cluster_ca_certificate = base64decode(module.gke_cluster.cluster_ca_certificate)
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

  depends_on = [module.gke_cluster]
}

resource "kubernetes_namespace" "reactive_observability" {
  metadata {
    name = "reactive-observability"
    labels = {
      "app.kubernetes.io/part-of" = "reactive-system"
    }
  }

  depends_on = [module.gke_cluster]
}

resource "kubernetes_namespace" "reactive_app" {
  metadata {
    name = "reactive-app"
    labels = {
      "app.kubernetes.io/part-of" = "reactive-system"
    }
  }

  depends_on = [module.gke_cluster]
}
