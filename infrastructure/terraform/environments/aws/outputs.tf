# AWS Environment Outputs

output "cluster_name" {
  description = "EKS cluster name"
  value       = module.eks_cluster.cluster_name
}

output "cluster_endpoint" {
  description = "EKS cluster endpoint"
  value       = module.eks_cluster.cluster_endpoint
}

output "configure_kubectl" {
  description = "Command to configure kubectl"
  value       = module.eks_cluster.configure_kubectl
}

output "vpc_id" {
  description = "VPC ID"
  value       = module.eks_cluster.vpc_id
}

output "cluster_oidc_provider_arn" {
  description = "OIDC Provider ARN for IRSA"
  value       = module.eks_cluster.cluster_oidc_provider_arn
}
