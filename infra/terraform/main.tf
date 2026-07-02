resource "azurerm_resource_group" "rg" {
  name     = var.resource_group_name
  location = var.location
}

# The actual Managed Kubernetes Cluster (AKS)
resource "azurerm_kubernetes_cluster" "aks" {
  name                = var.cluster_name
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  dns_prefix          = var.dns_prefix
  sku_tier = "Free"
  # System Node Pool — DSv4 family (quota: 2/10 vCPU)
  # Standard_D2s_v4: 2 vCPU, 8 GiB RAM
  # Hosts only AKS system pods (CoreDNS, kube-proxy, metrics-server).
  default_node_pool {
    name       = "systempool"
    node_count = var.system_node_count
    vm_size    = var.system_node_size

    # We only want AKS system pods (CoreDNS) to run here
    only_critical_addons_enabled = true
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin    = "kubenet"
    load_balancer_sku = "standard"
  }
}

# Central Node Pool — DDSv4 family (quota: 8/10 vCPU)
# Standard_D8ds_v4: 8 vCPU, 32 GiB RAM, local temp SSD
# Hosts: Kafka, Central DB, Redis, Keycloak, payment-consumers,
#         payment-central-relay, monitoring stack (Prometheus+Grafana).
resource "azurerm_kubernetes_cluster_node_pool" "central" {
  name                  = "centralpool"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.aks.id
  vm_size               = var.central_node_size
  node_count            = var.central_node_count

  node_labels = {
    "pool" = "central"
  }
}

# Edge Node Pool 1 — DSv3 family (quota: 8/10 vCPU)
# Standard_D8s_v3: 8 vCPU, 32 GiB RAM
# Hosts the primary payment-edge-cell pod (payment-service + edge-db + edge-workers sidecar).
# Fixed at 1 node — the primary edge cell that is always running.
resource "azurerm_kubernetes_cluster_node_pool" "edge" {
  name                  = "edgepool"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.aks.id
  vm_size               = var.edge_node_size
  node_count            = var.edge_node_count

  node_labels = {
    "pool" = "edge"
  }
}

# Edge Node Pool 2 — EDSv4 family (quota: 0-8/10 vCPU)
# Standard_E8ds_v4: 8 vCPU, 64 GiB RAM
# Autoscale pool: scales from 0→1 when HPA triggers a 2nd edge cell pod.
# Uses a DIFFERENT VM family than edgepool to stay within the per-family
# 10 vCPU quota constraint (DSv5 quota was rejected).
resource "azurerm_kubernetes_cluster_node_pool" "edge2" {
  name                  = "edgepool2"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.aks.id
  vm_size               = var.edge2_node_size

  # Autoscaler: 0 nodes at rest, 1 node when HPA demands a 2nd edge cell.
  auto_scaling_enabled = true
  min_count            = var.edge2_min_count
  max_count            = var.edge2_max_count

  node_labels = {
    "pool" = "edge"
  }
}
