# GCP GKE Module Variables

variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "cluster_name" {
  description = "Name of the GKE cluster"
  type        = string
  default     = "reactive-system"
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "us-central1"
}

variable "zones" {
  description = "GCP zones for the cluster"
  type        = list(string)
  default     = ["us-central1-a", "us-central1-b", "us-central1-c"]
}

variable "network_name" {
  description = "Name of the VPC network"
  type        = string
  default     = "reactive-system-vpc"
}

variable "subnet_cidr" {
  description = "CIDR for the subnet"
  type        = string
  default     = "10.0.0.0/20"
}

variable "pods_cidr" {
  description = "Secondary CIDR for pods"
  type        = string
  default     = "10.1.0.0/16"
}

variable "services_cidr" {
  description = "Secondary CIDR for services"
  type        = string
  default     = "10.2.0.0/20"
}

variable "kubernetes_version" {
  description = "Kubernetes version for GKE"
  type        = string
  default     = "1.28"
}

variable "node_machine_type" {
  description = "Machine type for nodes"
  type        = string
  default     = "e2-standard-4"
}

variable "node_count" {
  description = "Initial number of nodes per zone"
  type        = number
  default     = 1
}

variable "node_min_count" {
  description = "Minimum number of nodes per zone"
  type        = number
  default     = 1
}

variable "node_max_count" {
  description = "Maximum number of nodes per zone"
  type        = number
  default     = 5
}

variable "environment" {
  description = "Environment name (dev, staging, production)"
  type        = string
  default     = "production"
}

variable "enable_horizontal_pod_autoscaler" {
  description = "Enable horizontal pod autoscaler"
  type        = bool
  default     = true
}

variable "enable_network_policy" {
  description = "Enable network policy"
  type        = bool
  default     = true
}
