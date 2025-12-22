# GCP Environment Outputs

output "cluster_name" {
  description = "GKE cluster name"
  value       = module.gke_cluster.cluster_name
}

output "cluster_endpoint" {
  description = "GKE cluster endpoint"
  value       = module.gke_cluster.cluster_endpoint
}

output "configure_kubectl" {
  description = "Command to configure kubectl"
  value       = module.gke_cluster.configure_kubectl
}

output "network_name" {
  description = "VPC network name"
  value       = module.gke_cluster.network_name
}

output "workload_identity_pool" {
  description = "Workload Identity pool"
  value       = module.gke_cluster.workload_identity_pool
}
