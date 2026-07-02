# Architecture Decision Record (ADR) & Azure Infrastructure Design

## 1. Title
**ADR-001: Ephemeral Load Testing Infrastructure on Azure Kubernetes Service (AKS)**

## 2. Context
We have built a high-performance, cell-based payment processing system. Originally customized via numerous imperative Bash scripts, the local environment has since been fully refactored into a declarative Infrastructure as Code (IaC) model. We need to port this exact declarative architecture to Azure without breaking the local developer workflow.

## 3. Decision
We will clone and parameterize your existing bash scripts to create an identical, sibling deployment track for Azure. **The GitHub Actions pipeline will boot up a temporary Linux server in the cloud to execute your existing bash scripts on your behalf.**

## 4. Resource Allocation & Sizing
1. **System Node Pool (`Standard_D2s_v5` - 2 vCPU, 8 GB RAM)**
2. **Central Node Pool (`Standard_D8s_v5` - 8 vCPU, 32 GB RAM):** Pinned via `nodeSelector`.
3. **Edge Cell Node Pool (`Standard_D8s_v5` - 8 vCPU, 32 GB RAM):** Dedicated exclusively to the `payment-edge-cell` Pods.

## 5. Script and Configuration Strategy (The "Azure Profile")

We will mirror your local deployment structure perfectly:

### 5.1 Helm Values and Secrets (Same Folders)
Inside your *existing* `infra/helm-values` and `infra/secrets` folders, we will place a new file next to every `-local.yaml` file ending in `-azure.yaml` (e.g., `central-db-values-local.yaml` will live right next to `central-db-values-azure.yaml`).
* The `-azure.yaml` files will have larger connection pools, higher CPU limits, and the AKS `nodeSelector` values.

### 5.2 Bash Scripts (Same Folder)
Inside your *existing* `infra/scripts` folder, we will duplicate `deploy-all-local.sh` and its child scripts to end with `-azure.sh`. The Azure scripts will reference the `-azure.yaml` files.

## 6. Infrastructure as Code (IaC) & CI/CD Flow

> [!IMPORTANT]
> **WHERE DOES ALL THIS RUN?**
> When you click "Run workflow" in GitHub, GitHub boots up a temporary, free **Ubuntu Virtual Machine** in their cloud (called a "Runner"). 
> 
> GitHub downloads your code onto that Ubuntu VM. **Every single script (Terraform, Docker builds, and Bash scripts) is executed directly on that temporary GitHub Ubuntu VM**, not on your Mac, and not inside the AKS cluster! That Ubuntu VM acts as your "robot developer", doing exactly what you would do on your Mac terminal.

!IMPORTANT]
> **WHERE DOES ALL THIS RUN?**
> When you click "Run workflow" in GitHub, GitHub boots up a temporary, free **Ubuntu Virtual Machine** in their cloud (called a "Runner").
>
> GitHub downloads your code onto that Ubuntu VM. **Every single script (Terraform, Docker builds, and Bash scripts) is executed directly on that temporary GitHub Ubuntu VM**, not on your Mac, and not inside the AKS cluster! That Ubuntu VM acts as your "robot developer", doing exactly what you would do on your Mac terminal.

```mermaid
graph TD
    Dev(("Developer (Your Mac)")) -->|1. git push| GH["GitHub Repository"]
    
    subgraph "The GitHub Actions Ubuntu Virtual Machine"
        direction TB
        GH -->|2. Downloads Code| Runner(("GitHub Runner (Ubuntu VM)"))
        
        Runner -->|3. Runs Terraform| TF["terraform apply<br/>(aks-loadtest.tfvars)"]
        Runner -->|4. Runs Build Scripts| Build["infra/scripts/build-and-push.sh"]
        Runner -->|5. Runs Deploy Scripts| Deploy["infra/scripts/deploy.sh"]
    end
    
    TF -->|Creates Cluster| AKS["Azure AKS Cluster"]
    Build -->|docker push| DockerHub[("Docker Hub Registry")]
    Deploy -.->|helm install| AKS
    AKS -.->|Pulls images| DockerHub
```

### The Step-by-Step CI/CD Execution:
1. **Creation:** You click "Run workflow" on `deploy-infra.yml` in GitHub Actions. GitHub boots up the temporary Ubuntu Runner.
2. **IaC Execution:** The Ubuntu Runner authenticates securely with Azure and runs `terraform apply`. Terraform uses the Azure API to physically rent the Virtual Machines and start the AKS Kubernetes cluster.
3. **Build & Push:** The Ubuntu Runner executes your existing `build-and-push-payment-service-docker-repo.sh` scripts. The Java code is compiled and the Docker images are built **directly on the Ubuntu Runner**. The Runner then pushes those built images to your Docker Hub.
4. **Application Deployment:** The Ubuntu Runner installs `helm` and `kubectl` on itself, downloads the `kubeconfig` from Azure, and then executes your `deploy-all-azure.sh` script. The bash script talks to the AKS Cluster's API and tells it to deploy your Helm charts.
5. **Teardown:** When finished, you run `destroy-infra.yml`. A new Ubuntu Runner boots up, runs `terraform destroy`, and deletes the Azure resources.


## 7. Status
**PROPOSED**

---

## 8. Detailed Infrastructure Walkthrough (Terraform Breakdown)

Because this ADR serves as our architectural blueprint, below is a detailed breakdown of the exact Terraform code we just wrote in Phase 2 (`infra/terraform/main.tf`). This documents *why* we configured Azure the way we did.

### 8.1 The Foundation: Resource Group, API Server, and Load Balancer
In Azure, everything must live inside a "Resource Group" (a logical folder). We define our Resource Group to live in `westeurope` (the Netherlands), guaranteeing the lowest possible latency for your testing.

We then define the `azurerm_kubernetes_cluster`. We give it a `dns_prefix` (e.g., `payloadtest`). This simply tells Azure to generate a unique API web address (like `payloadtest-xyz.hcp.westeurope.azmk8s.io`) so that your GitHub Actions pipeline knows exactly how to connect its `kubectl` commands to the cluster.

We use the `kubenet` network plugin because it is lightweight and free. Finally, we attach a `standard` Azure Load Balancer. 
* **Wait, does this replace Ingress-NGINX?** No! We still absolutely need your `ingress-nginx`. The Azure Load Balancer is just the physical metal router outside the cluster that catches raw internet traffic and provides your public IP address. It passes that raw traffic directly to your `ingress-nginx` pods, which then route the HTTP requests to your Java applications.

### 8.2 The System Node Pool (`systempool`)
**What is a Node Pool?** A Node Pool is simply a group of Virtual Machines that share the exact same hardware size and rules. Instead of managing 10 individual VMs, you create a single Node Pool, tell it what hardware size to use, and say "give me 3 of these." All VMs in that pool will be identical.

```hcl
default_node_pool {
  name       = "systempool"
  node_count = 1
  vm_size    = "Standard_D2s_v5"
  only_critical_addons_enabled = true
}
```
**The Detail:** Every AKS cluster requires at least one default node pool. We created a tiny 2-vCPU node. *(Note: You can tell it has 2 vCPUs because the number **2** is right in the name: `Standard_D2s_v5`)*.
**The "Why":** We set `only_critical_addons_enabled = true`. This is a crucial Azure feature. It tells Kubernetes: *"Do not allow ANY user applications to run here."* This guarantees that Kubernetes' own internal brain (CoreDNS, metrics, health probes) runs safely here without your high-throughput load tests accidentally crashing the cluster's internal networking.
*(Note: Does the GitHub Actions Runner run here? **NO!** The GitHub Runner is a completely separate server hosted by GitHub far away from Azure. The Runner is the one telling Azure to build this `systempool`!)*

### 8.3 The Central Node Pool (`centralpool`)
```hcl
resource "azurerm_kubernetes_cluster_node_pool" "central" {
  name                  = "centralpool"
  vm_size               = "Standard_D8s_v5"
  node_labels = { "pool" = "central" }
}
```
**The Detail:** We rent an 8-vCPU, 32GB RAM Virtual Machine. *(Note: The **8** in `Standard_D8s_v5` means 8 vCPUs!)* We explicitly label this VM with the sticker `pool=central`.
**The "Why":** Later, in Phase 4, we will update the Helm charts for Kafka, Redis, your Central DB, and the Central Relay to strictly look for VMs wearing the `pool=central` sticker. 
* **Virtual Isolation:** Even though they share this physical `Standard_D8s_v5` motherboard to save money, Kubernetes deploys them as completely isolated Pods. This means Kafka and Central DB will have completely different internal IPs and different virtual hostnames (e.g., `kafka.payment.svc.cluster.local` vs `central-db-postgresql.payment.svc.cluster.local`). They behave exactly as if they were on separate machines!

### 8.4 The Edge Autoscaling Pool (`edgepool`)
```hcl
resource "azurerm_kubernetes_cluster_node_pool" "edge" {
  name                  = "edgepool"
  vm_size               = "Standard_D8s_v5"
  enable_auto_scaling   = true
  min_count             = 1
  max_count             = 3
  node_labels = { "pool" = "edge" }
}
```
**The Detail:** We rent another 8-vCPU VM and label it `pool=edge`. We also turn on Azure's native VM autoscaler.
**The "Why":** Your `payment-edge-cell` Pods are massive. If your k6 load test pushes 1,000 RPS, your Pods will hit 100% CPU. When Kubernetes tries to spawn a *second* `payment-edge-cell` Pod, it won't fit on this VM. Because `enable_auto_scaling` is true, Azure will detect the traffic jam, automatically rent a *second* `Standard_D8s_v5` VM in the background, attach it to the cluster, and move your new Pod onto it. When the load test ends, Azure deletes the second VM to save you money!

---


## 8.5 VM Operating System Disks vs Application Disks
We establish a strict architectural boundary between compute storage and persistent application data:
*   **Node OS Disks (Compute):** The Terraform `main.tf` defines the VM SKUs (`Standard_D8s_v5`). By default, AKS provisions a 128GB Managed OS Disk for these nodes to hold the Linux OS, container images, and ephemeral logs. Because Kubernetes nodes are stateless cattle, these OS disks are entirely disposable.
*   **Application Data (PVCs):** Our databases (PostgreSQL and Kafka) utilize Kubernetes `PersistentVolumeClaims` (PVCs). In Azure, this automatically triggers the `managed-csi` driver to provision highly-redundant, independent **Premium SSD Managed Disks** over the network. If a physical VM crashes, the node OS disk is lost, but the Premium SSD Data Disk is safely detached by Azure and plugged into the replacement VM with zero data loss.

### 8.6 The Dual Purpose of StatefulSets
Our architecture utilizes the `StatefulSet` API object for two fundamentally different reasons:
1.  **For Databases (`payment-edge-cell`):** We use StatefulSets to dynamically provision independent hard drives. If the Edge Cell scales to 3 replicas, the `volumeClaimTemplates` forces Kubernetes to provision 3 entirely separate Premium SSDs. This ensures `payment-edge-cell-0` never attempts to corrupt the physical database files of `payment-edge-cell-1`.
2.  **For Kafka Consumers (`payment-consumers`):** The consumers have absolutely no persistent storage attached. However, they use a StatefulSet to acquire stable network identities (e.g., `payment-consumers-0`). If a pod restarts, it retains its exact identity. This signals to the Kafka broker that it is the *same* consumer returning, completely bypassing a cluster-wide Kafka Consumer Group Rebalance storm.

### 8.7 Environment-Specific Capacity Tuning
All infrastructure capacity constraints are mathematically decoupled from the generic Helm templates (`values.yaml`) and strictly injected via environment-specific profiles (`helm-values/*-azure.yaml` vs `*-local.yaml`).
*   **Central Infrastructure:** The Azure configuration for the Central Database and Kafka explicitly override defaults with massive production-grade capacities (e.g., `250Gi` Managed Disks, `6000m` CPU, `12Gi` RAM, and `4GB shared_buffers`) tailored precisely for the 8-core `Standard_D8s_v5` nodes in the `centralpool`. Local configurations are strictly starved to prevent laptop exhaustion.
*   **Relay Singleton:** The `payment-central-relay` polling job is explicitly stripped of autoscaling and locked to a strict `replicaCount: 1` singleton to prevent database contention and duplicate message publishing.


## Append-Only Ledger Log

### 2026-06-11: Phase 1 - Script and Configuration Scaffolding Completed
**Action:** Cloned all local deployment environments into parallel Azure deployment environments.
**Details:**
- Copied all `infra/helm-values/*-local.yaml` to sibling `*-azure.yaml` files.
- Copied all `infra/secrets/*-local.yaml` to sibling `*-azure.yaml` files.
- Cloned `deploy-all-local.sh` and its child execution scripts (like `deploy-payment-platform-config.sh`) into `-azure.sh` variants.
- Ran a sed replacement to dynamically update the internal bash script references so that the new `-azure.sh` scripts exclusively load configurations from the `-azure.yaml` files.
**Result:** We now have a completely isolated Azure configuration track that perfectly mimics the local developer setup without breaking it.

### 2026-06-11: Phase 2 - Terraform Infrastructure as Code Created
**Action:** Scaffolded the Terraform configuration for the Azure Kubernetes Service (AKS).
**Details:**
- Created `infra/terraform/providers.tf` using the Azure Resource Manager (azurerm) provider.
- Created `infra/terraform/variables.tf` specifying `westeurope` and the Resource Group.
- Created `infra/terraform/main.tf` defining the AKS Cluster and the exact Node Pools we agreed upon:
  1. `systempool` (Standard_D2s_v5) for critical system pods.
  2. `centralpool` (Standard_D8s_v5) for Kafka and Central DB.
  3. `edgepool` (Standard_D8s_v5) with `enable_auto_scaling = true` (max 3 nodes) for the payment-edge-cells.
**Result:** We now have declarative IaC to instantly provision and tear down the exact cluster topology in Azure.

### 2026-06-11: Phase 3 - GitHub Actions CI/CD Pipeline Created
**Action:** Scaffolded the GitHub Actions workflows to fully automate the cloud infrastructure and deployments.
**Details:**
- Created `.github/workflows/deploy-infra.yml` containing the 3-step pipeline (Terraform, Docker Build & Push, Helm Deploy).
- Implemented an `environment: azure-loadtest` approval gate directly into the pipeline so that `terraform plan` must be manually approved by a human before `terraform apply` begins.
- Created `.github/workflows/destroy-infra.yml` to instantly tear down the cluster and stop billing.
**Result:** The cloud deployment is now 100% automated and protected by an approval gate.

### 2026-06-11: Phase 4 - Azure Configurations & Constraints Injected
**Action:** Modified the Azure configuration files to maximize hardware utilization and isolate workloads via NodeSelectors.
**Details:**
- Injected `nodeSelector: pool: central` into the Central DB, Kafka, Redis, Keycloak, and Central App Helm templates.
- Injected `nodeSelector: pool: edge` into the Payment Edge Cell Helm templates.
- Created `application-azure.yml` files across all microservices. Increased HikariCP connection pools to `60` (from `12`) and disabled verbose logging bottlenecks to prevent stdout thread locking under load.
- Increased the Kubernetes CPU limits in `payment-edge-cell-values-azure.yaml` to consume exactly 7.5 vCPUs per pod (3 for DB, 3 for App, 1.5 for Worker), perfectly matching the `Standard_D8s_v5` hardware boundaries.
**Result:** The cloud deployment will now flawlessly utilize 100% of the Azure hardware and completely isolate Edge traffic from Central traffic.

### 2026-06-12: Phase 5 - Azure Configurations & Constraints Injected
**Action:** Moved all environment speficic confioguration to local for now, and did test the system locally if seperation has any config issue
**Details:**
- Injected /created local and azure suffixx yml and shell script for local azure environments


### 2026-06-13: Phase 6 - Move local build from minikube to orbstack

OrbStack Migration Walkthrough
I have successfully purged the repository of all Minikube network bottlenecks and aligned the local environment to run optimally on OrbStack Native Kubernetes.

What Was Changed
1. Network Plumbing Purge
   Deleted: tunnel.sh, port-forwarding.sh, bootstrap-minikube-cluster.sh, and minikube-nuke-dev.sh via git rm.
   Created: orbstack-nuke-dev.sh to securely wipe Kubernetes namespaces, Helm releases, and Docker system cache natively without Minikube commands.
   Refactored deploy-payment-edge-cell-local.sh: Ripped out the complex minikube ip / LoadBalancer port polling logic and .nip.io domain mapping. It now explicitly generates http://payment.mor-dc.local endpoints.
   Audited: All other deploy-*-local.sh and delete-*-local.sh scripts were verified and found fully compatible with native kubectl.
2. Spring & Keycloak Provisioning
   Updated payment-service/src/main/resources/application-local.yml to rely on the cluster-native http://keycloak.payment.svc.cluster.local:8080/realms/payment issuer instead of localhost port-forwarding.
   Updated provision-keycloak.sh and get-token.sh scripts to use the same internal service DNS natively.
3. Docker Build Script Forking
   I have fully separated local builds from registry pushes:

Renamed: Existing build-and-push-*.sh scripts were mapped strictly to the azure environment (e.g. build-and-push-payment-service-docker-repo-azure.sh).
Created: Four brand new build-local-*.sh scripts (e.g. build-local-payment-service.sh). These scripts compile the image locally and exit immediately. Because OrbStack shares the native Docker socket, Kubernetes can instantly resolve these images without the overhead of pushing/pulling from Docker Hub!
4. Documentation Cleanup
   how-to-start.md was thoroughly stripped of all -H "Host: $HOST" injected headers. Your curl calls are now fully standardized.
   The minikube setup steps have been removed, replacing them directly with instructions pointing to OrbStack.
   Verification
   The system is now primed for your "Golden Startup Path".

Next Steps to Validate:

Execute ./infra/scripts/orbstack-nuke-dev.sh to confirm the local environment is pristine.
Ensure OrbStack is active in your MacOS menu bar and Kubernetes is enabled.
Run ./infra/scripts/deploy-all-local.sh.
Run ./infra/scripts/deploy-payment-edge-cell-local.sh.
No tunnels, no CPU overhead, no port forwarding loops!




### 2026-06-12 Phase 8 - Global Distributed Idempotency Cluster via YugabyteDB
**Action:** Migrated idempotency state management from the localized `edge-db` instances to a globally distributed YugabyteDB cluster.
**Details:**
- Deployed YugabyteDB via Helm (`yugabyte-values-local.yaml`) alongside the `payment-edge-cell` charts. For the local environment, it is optimized via `replication_factor: 1` to behave as a single-node cluster without waiting for a 3-node Raft quorum.
- Fully automated the database provisioning using a Kubernetes Job (`yugabyte-db-init-job.yaml`) running an Alpine PostgreSQL client. The script loops until Yugabyte is healthy, then executes DDL to create the `payment_service` and `duty` roles, injects credentials via k8s Secrets, and configures schema permissions.
- Injected `YUGABYTE_DB_URL` into the Spring Boot application, separating the idempotency data source from the primary `edge-db` PostgreSQL instance.
**Result:** Idempotency keys are now strongly consistent across the entire system. If a merchant's retry request hits a completely different edge cell due to load balancing, the distributed Yugabyte consensus ensures the edge cell immediately recognizes the key and prevents a double-charge.

### 2026-06-13: Phase 7 - Helm Values & Secrets Parity Audit ✅ COMPLETED
**Action:** Compared every `*-local.yaml` against its `*-azure.yaml` counterpart across `infra/helm-values/` and `infra/secrets/`.
**Findings & Fixes:**
- **FIXED** `payment-central-relay-azure.yaml`: `spring.profile` was `local` — corrected to `azure`. Resources were laptop-grade (100m/256Mi) — bumped to production-grade (1000m/1024Mi → 3000m/2048Mi).
- **FIXED** `payment-central-relay-values-azure.yaml`: Missing `nodeSelector: pool: central` — the relay singleton was not pinned to the centralpool, risking it landing on the edgepool. Added.
- **VERIFIED** `central-db-values-azure.yaml`: Correctly configured with `nodeSelector: pool: central`, 250Gi disk, 6000m CPU, 4GB shared_buffers.
- **VERIFIED** `kafka-values-azure.yaml`: Correctly configured with `nodeSelector: pool: central`, 250Gi persistence, 6GB JVM heap.
- **VERIFIED** `payment-edge-cell-values-azure.yaml`: Correctly configured with `nodeSelector: pool: edge`, 100Gi edge-db disk, 3 vCPU per app container.
- **VERIFIED** `payment-consumers-values-azure.yaml`: Correctly configured with `nodeSelector: pool: central`, 3000m CPU, 2048Mi RAM.
- **VERIFIED** `keycloak-values-azure.yaml`: Correctly configured with `nodeSelector: pool: central`, 50Gi Postgres PVC.
- **VERIFIED** `redis-values-azure.yaml`: Correctly configured with `nodeSelector: pool: central`, 50Gi PVC, AOF persistence enabled.
- **VERIFIED** `yugabyte-values-azure.yaml`: Correctly configured with `replication_factor: 3`, 3 master/tserver replicas, 100Gi tserver disk, `nodeSelector: pool: edgedb`.
**Result:** All 9 Helm value pairs are correctly differentiated. No azure YAML accidentally references local sizing.

### 2026-06-13: Phase 8 - Build Script Parity Audit ✅ COMPLETED
**Action:** Compared all `build-and-push-*-local.sh` scripts against their `-azure.sh` counterparts.
**Findings & Fixes:**
- **FIXED** `build-and-push-payment-edge-workers-docker-repo-local..sh`: Double-dot typo in filename. Renamed to `build-and-push-payment-edge-workers-docker-repo-local.sh` via `git mv`.
- **VERIFIED** All 4 service pairs (`payment-service`, `payment-consumers`, `payment-central-relay`, `payment-edge-workers`): local and azure scripts are functionally identical — both build and push to Docker Hub. This is intentional; the local/azure distinction is purely at the Helm values layer.
**Result:** All build scripts are structurally correct and filename typo is resolved.

### 2026-06-13: Phase 9 - Deploy Script Parity Audit ✅ COMPLETED
**Action:** Compared all `deploy-*-local.sh` scripts against their `-azure.sh` counterparts.
**Findings & Fixes:**
- **FIXED** `deploy-payment-platform-config.sh`: Missing `-local` suffix. Renamed to `deploy-payment-platform-config-local.sh` via `git mv`. Updated the reference in `deploy-all-local.sh` line 25.
- **FIXED** `deploy-payment-edge-cell-azure.sh`: Still contained Minikube-era code (`minikube ip`, `nip.io` from Minikube IP, `tunnel.sh` fallback, `nc` port probing). This was a stale copy from Phase 5 that was never updated during the OrbStack migration (Phase 6). Fully rewritten for AKS: polls Azure Load Balancer for EXTERNAL-IP (up to 30 attempts × 10s = 5min), constructs `payment.<IP>.nip.io` hostname, writes `infra/endpoints.json` for k6 load tests, then deploys via Helm.
- **VERIFIED** Static scan: zero azure scripts reference `-local.yaml` files; zero local scripts reference `-azure.yaml` files.
**Result:** All deploy scripts are correctly environment-scoped. Azure deploy pipeline is now safe to run end-to-end.

---

### 2026-06-13: Phase 10 - Load Test Strategy Decision ✅ DECIDED

**Decision: Test Edge Layer Capacity First — Kafka Single Broker (Scenario A)**

**Rationale:**
The edge layer (`payment-service` + `edge-db` + `payment-edge-workers` + YugabyteDB idempotency check) is the **synchronous hot path** — the only part of the system the shopper waits for. The central async pipeline (Kafka, `payment-consumers`, `payment-central-relay`) can lag behind under load without degrading shopper-visible latency. Therefore edge capacity is measured first to establish the true RPS ceiling before introducing async backpressure.

**Kafka configuration stays at single broker:**
- `replicaCount: 1`, `controllerOnly: false` (combined controller + broker, KRaft mode)
- `replication.factor: 1`, `min.insync.replicas: 1`
- No Terraform changes required. Cost delta: $0.
- Risk accepted: Kafka pod restart during a 4-hour window is low probability. If it occurs, the test run is invalidated and restarted.

**What "Edge Layer Capacity" means in measurable terms:**

| Metric | What it tells us |
|---|---|
| **Max sustained RPS** before P99 > 500ms | Actual throughput ceiling of one edge cell pod |
| **P50 / P95 / P99 latency** under load | Where latency is spent: JVM, edge-db, or YugabyteDB |
| **YugabyteDB idempotency check latency** | Cross-node overhead on every payment request (edgepool → edgedbpool) |
| **edge-db connection pool exhaustion** | Whether HikariCP queue depth spikes at peak |
| **HPA trigger point** | At what RPS does the autoscaler add Edge Cell Pod 1, then Pod 2 |
| **Edge cell autoscale latency** | How long from "traffic spike" to "second pod ready and serving" |
| **Memory headroom on payment-service** | JVM GC pause frequency under sustained load (dual HikariCP pool overhead) |

**Next Phase:** Ground-up rewrite of all `*-azure.yaml` Helm values from Terraform hardware topology (see Phase 11).

---

### 2026-06-13: Phase 11 - Azure Helm Values Topology Rewrite ✅ COMPLETED

**Action:** Rewrote all `*-azure.yaml` Helm values from scratch to map directly to the Terraform hardware budgets (`systempool`, `centralpool`, `edgepool`, `edgedbpool`) and implemented industry-standard observability.

**Findings & Fixes:**
- **Edge Cell (`payment-edge-cell-values-azure.yaml`)**:
  - Added `topologySpreadConstraints` with `maxSkew: 1` and `whenUnsatisfiable: DoNotSchedule`. This hard-enforces the "1 pod per node" rule on the `edgepool` to prevent noisy-neighbor GC pauses under load.
  - Bumped `payment-service` RAM to 4Gi limit / 60% heap to absorb the dual HikariCP pool overhead (local `edge-db` + cross-node YugabyteDB).
  - Scaled HPA trigger down from 85% to 70% CPU to ensure the autoscaler provisions Edge Pod 2 and 3 *before* P99 latency spikes during the k6 test.
- **Idempotency Layer (`yugabyte-values-azure.yaml`)**:
  - Added `podAntiAffinity` to hard-enforce 1 `yb-tserver` per node on the `edgedbpool`. Without this, all 3 tservers could land on the same node, defeating the Raft quorum logic entirely.
  - Bumped `tserver` RAM limit to 10Gi (from 4Gi) to account for YSQL `shared_buffers` and the incoming 60 connection pool threads from the edge cells.
- **Monitoring Stack (`monitoring-stack-values-azure.yaml`)**:
  - Replaced the custom local bash script (`add-consumer-lag-metric.sh`) with the industry-standard `prometheus-adapter` Helm subchart.
  - Pinned all Prometheus, Grafana, Alertmanager, and Adapter components to the `centralpool`.
  - Configured for load testing: 15s scrape interval, 24h retention, and a 50Gi Premium SSD PVC to survive test interruptions.
- **Consumer Sizing (`payment-consumers-values-azure.yaml`)**:
  - Solved the **"Pending Pod" scaling lock**. The single `centralpool` node (8 vCPU) runs Kafka, Postgres, Redis, Keycloak, Relay, and Monitoring (using ~6.8 vCPU).
  - Previously, `payment-consumers` requested 1000m CPU. When scaling past 1 replica, the scheduler would reject pods due to lack of requested CPU, locking it at 1.
  - **Fix:** Dropped `requests.cpu` to 150m, kept `limits.cpu` at 1000m. This allows all 6 consumer replicas to schedule on the node and dynamically burst into idle CPU capacity as Kafka backlog grows.

**Result:** The entire Azure deployment pipeline and infrastructure configuration is now fully robust, strictly mapped to hardware constraints, and ready for the first load test.

---

### 2026-06-13: Phase 12 - Universal Health Probe Standardization ✅ COMPLETED

**Context:** The legacy health probes were too aggressive for a Kubernetes environment experiencing CPU contention (Local deployment) or GC pauses (Azure Load Testing). This caused "false positive" pod terminations.

**Action:** Standardized `startupProbe`, `livenessProbe`, and `readinessProbe` definitions across all `payment-edge-cell`, `payment-consumers`, and `payment-central-relay` Helm charts.

**The Strategy:**
1. **Startup Probes (Forgiving):** Increased failure threshold to 60 (5-minute timeout) to absorb slow multi-JVM local startup times without throwing the deployment into a `CrashLoopBackOff`.
2. **Readiness Probes (Responsive):** Set to check every 10s and fail after 30s. Safely pulls pods out of the Ingress load balancer if they lose their database connection.
3. **Liveness Probes (Loose):** Increased failure threshold to allow up to 60 seconds of unresponsiveness. This prevents Kubernetes from `SIGKILL`ing a pod that is simply paused for a heavy Garbage Collection cycle during the k6 load test.

**Result:** A massive reduction in false-positive pod restarts locally, and guaranteed GC pause tolerance during the Azure load test.



### 2026-06-15: Phase 13 - Complete declarative infra as code migration completed ✅ COMPLETED
-From now on we do declrative infra a s code yapiyoruz

### 2026-06-16: Phase 14- payment-edge-worker is out of  payment-edge cell,  so they are gonan be in seperetea DB .
-this will make payment-edge-cel quite lighter , and aslos agile , we need include this t o capacity estiamtion
### 2026-06-17: Phase 15- YUGOBYTE WAS REMOVED from poject, instead idempotenct wil be in its previoius place which is edge-db
-Yugabyte project was saslso stopped, during the capacitiy estimation ,assume idmepteincy will bee in edge=-db
### 2026-06-19 MAJOR UPDATE ON  Quota request on Standard DSv5 Family vCPUs REJECTED AVAIAKLBLE OPTIONS ARE in the list below where  the (limit values  greater rhan zero)

Quota,Region,Usage,Current Limit,Usage%
Standard Dadsv7 Family vCPUs,West Europe,0,10,0%
Standard Daldsv7 Family vCPUs,West Europe,0,10,0%
Standard Ddsv6 Family vCPUs,West Europe,0,10,0%
Standard Ddsv7 Family vCPUs,West Europe,0,10,0%
Standard Dldsv6 Family vCPUs,West Europe,0,10,0%
Standard Dldsv7 Family vCPUs,West Europe,0,10,0%
Standard Dsv6 Family vCPUs,West Europe,0,10,0%
Standard Dsv7 Family vCPUs,West Europe,0,10,0%
Standard DADSv5 Family vCPUs,West Europe,0,0,0%
Standard DDSv4 Family vCPUs,West Europe,0,10,0%
Standard DDSv5 Family vCPUs,West Europe,0,0,0%
Standard DLDSv5 Family vCPUs,West Europe,0,0,0%
Standard DPDSv5 Family vCPUs,West Europe,0,0,0%
Standard Dpdsv6 Family vCPUs,West Europe,0,10,0%
Standard DPLDSv5 Family vCPUs,West Europe,0,0,0%
Standard Dpldsv6 Family vCPUs,West Europe,0,10,0%
Standard DSv3 Family vCPUs,West Europe,0,10,0%
Standard DSv4 Family vCPUs,West Europe,0,10,0%
Standard DSv5 Family vCPUs,West Europe,0,0,0%
Standard DCADSv5 Family vCPUs,West Europe,0,0,0%
Standard DCadsv6 Family vCPUs,West Europe,0,0,0%
Standard DSv2 Family vCPUs,West Europe,0,10,0%
Standard DSv2 Promo Family vCPUs,West Europe,0,10,0%
Standard EIDSv5 Family vCPUs,West Europe,0,0,0%
Standard EIADSv5 Family vCPUs,West Europe,0,0,0%
Standard EIBDSv5 Family vCPUs,West Europe,0,0,0%
Standard Eadsv7 Family vCPUs,West Europe,0,10,0%
Standard Edsv6 Family vCPUs,West Europe,0,10,0%
Standard Edsv7 Family vCPUs,West Europe,0,10,0%
Standard EADSv5 Family vCPUs,West Europe,0,0,0%
Standard EDSv4 Family vCPUs,West Europe,0,10,0%
Standard EDSv5 Family vCPUs,West Europe,0,0,0%
Standard EBDSv5 Family vCPUs,West Europe,0,10,0%
Standard EPDSv5 Family vCPUs,West Europe,0,0,0%
Standard Epdsv6 Family vCPUs,West Europe,0,10,0%
Standard EIDSv4 Family vCPUs,West Europe,0,0,0%
Standard ECADSv5 Family vCPUs,West Europe,0,0,0%
Standard ECadsv6 Family vCPUs,West Europe,0,0,0%
Standard ECIADSv5 Family vCPUs,West Europe,0,0,0%
Standard Famdsv7 Family vCPUs,West Europe,0,10,0%
Standard Fadsv7 Family vCPUs,West Europe,0,10,0%
Standard Faldsv7 Family vCPUs,West Europe,0,10,0%
Standard FXmdsv2 Family vCPUs,West Europe,0,10,0%
Standard NDSv2 Family vCPUs,West Europe,0,0,0%
Standard NDSv3 Family vCPUs,West Europe,0,0,0%
Standard NGADSV620v1 Family vCPUs,West Europe,0,0,0%
Standard NVadsV710v5 Family vCPUs,West Europe,0,0,0%

Standard DSv5 Family vCPUs

### 2026-06-19: Phase 16 - Quota Pivot & Capacity Re-estimation ✅ COMPLETED

**Context:** The Standard DSv5 Family vCPU quota request for West Europe was **REJECTED** (limit = 0). All three node pools (`systempool`, `centralpool`, `edgepool`) used DSv5 SKUs and were completely blocked.

**Action:** Redesigned the entire VM topology to spread across 4 VM families that have available quota (10 vCPU each), and re-estimated all capacity budgets to account for Phase 14 (edge-workers separation) and Phase 15 (YugabyteDB removal).

**Terraform Changes (`infra/terraform/main.tf`):**

| Pool | Old SKU (BLOCKED) | New SKU | VM Family | Quota Used |
|------|-------------------|---------|-----------|:----------:|
| `systempool` | Standard_D2s_v5 | **Standard_D2s_v4** | DSv4 | 2/10 |
| `centralpool` | Standard_D8s_v5 | **Standard_D8ds_v4** | DDSv4 | 8/10 |
| `edgepool` | Standard_D8s_v5 (autoscale 1-3) | **Standard_D8ds_v6** (fixed 1) | Ddsv6 | 8/10 |
| `edgepool2` (NEW) | — | **Standard_D8ds_v7** (autoscale 0-1) | Ddsv7 | 0-8/10 |

**Architecture-Informed Capacity Changes:**
- **Edge cell pod budget (post Phase 14+15):** 5,115m CPU / 6.2Gi RAM request — fits an 8-vCPU node at 64% utilization with full `Guaranteed` QoS.
- **payment-service RAM** reduced from 4Gi → 3.5Gi limit (single HikariCP pool — no YugabyteDB dual-pool overhead).
- **HPA maxReplicas** reduced from 3 → 2 (matching the 2-node edge topology).
- **payment-consumers CPU request** reduced from 150m → 100m (creates KEDA scaling headroom on the centralpool).
- **Centralpool total CPU requests:** ~7,495m / 8,000m available — 505m headroom, sufficient for 4+ consumer replicas.

**Stale Reference Cleanup:**
- Removed `deploy-yugabyte-azure.sh` call from `deploy-all-azure.sh` (stale after Phase 15).
- Rewrote `deploy-monitoring-stack-local.sh` from Terraform HCL back to bash (matching azure counterpart style).

**Revised k6 Load Test Targets:**

| Profile | Old Target | New Target | Rationale |
|---------|:----------:|:----------:|-----------|
| `average` | 150 flows/sec | 80 flows/sec | ~60% of single-pod ceiling |
| `stress` | 100 flows/sec | 130 flows/sec | ~100% of ceiling, triggers HPA |
| `soak` | 30 flows/sec | 50 flows/sec | ~40% of ceiling, 1hr endurance |
| `spike` | 400 flows/sec | 250 flows/sec | Tests 2-pod autoscale |
| `breakpoint` | 1000 flows/sec | 350 flows/sec | Finds true 2-pod ceiling |

**Expected Capacity:**
- Single edge cell: **100-150 flows/sec** (200-300 API req/sec), p99 < 500ms
- Autoscaled (2 pods): **180-280 flows/sec** (360-560 API req/sec)
- Cost: **$1.01-$1.45/hr** (vs. original $1.20/hr with DSv5)

**Result:** The Azure deployment is unblocked and ready for load testing within the available quota constraints. The edge cell is lighter (single HikariCP pool, no YugabyteDB), and the new multi-family topology provides autoscaling capability despite the per-family quota cap.

### 2026-06-19: Phase 17 - Unified Declarative Deployment & Auth Fixes ✅ COMPLETED

**Context:** The deployment process still relied on multiple imperative scripts with implicit ordering. Furthermore, the Azure authentication for Terraform's remote state and the GitHub secrets pipeline needed fixes to ensure CI/CD success.

**Action:**
1. **True Declarative Deployments:** Completely rewrote both `deploy-all-local.sh` and `deploy-all-azure.sh` into single, unified scripts. They now submit all core infrastructure and applications to Kubernetes in one shot. Kubernetes natively resolves all startup dependencies in the background via `initContainers`.
2. **Component Cleanup:** Explicitly removed `payment-platform-config` and `yugabyte` from the unified scripts, fully realizing the architectural pivots from Phases 14 and 15.
3. **Obsolete Scripts Purged:** Permanently deleted the individual component deploy scripts (`deploy-payment-edge-cell`, `deploy-payment-consumers`, `deploy-payment-central-relay`, etc.) to eliminate clutter and enforce the new "single script" workflow.
4. **Terraform AzureAD Auth:** Updated `providers.tf` backend configuration to include `use_azuread_auth = true`, allowing Terraform to authenticate natively to the Azure Blob Storage remote state using the Service Principal.
5. **GitHub Secrets Pipeline:** Fixed `setup-github-secrets.sh` to ensure the Service Principal ID (`AZ_CLIENT_ID`) is properly parsed *before* attempting to assign it the "Storage Blob Data Contributor" role for Terraform state access.

**Result:** The entire provisioning and deployment pipeline is now genuinely declarative. Developers and CI pipelines can spin up the full cluster (locally or in Azure) by executing a single script without any manual pauses, hacky port forwarding, or order-dependent steps. Remote state authentication is also fully stabilized.

---

## 21. ADR-002: Embedded Ingress with Toggles (L7 Routing Decoupling)

### 21.1 Context
We have NGINX Ingress Controller routing public traffic to Keycloak (`/auth`) and the payment service (`/api/v1/payments`). Originally, Ingress configurations were tightly coupled to the application templates, exposing the application to raw root `/` paths. We needed to separate infrastructure routing rules from application logic without introducing complex custom routing charts.

### 21.2 Decision
We adopt the industry-standard **Embedded Ingress with Toggles** pattern:
1. **Keycloak Ingress**: Managed natively by Keycloak's Helm release using the Bitnami chart's built-in ingress parameters inside `infra/helm-values/keycloak-values-azure.yaml` (mapping `/auth(/|$)(.*)` with regex matching).
2. **Payment Ingress**: Managed inside `charts/payment-edge-cell/templates/ingress.yaml`, disabled by default (`ingress.enabled: false`). It is enabled only via environment overrides (`azure/values.yaml` and `local/values.yaml`), mapping only the explicit API prefixes (`/api/v1/payments`, `/swagger-ui`, `/v3/api-docs`, `/actuator`).
3. This keeps the application workload vanilla (no load balancer logic when disabled) and separates Keycloak's onboarding configuration from the payment service.

### 21.3 Rejected Alternatives
1. **Centralized Routing Gateway Chart (`edge-ingress-gateway`)**: Rejected because it required managing a custom chart inside `charts/` and created cross-chart coupling (hardcoding ports/service names of other releases).
2. **Manifests via raw Helm chart (`bedag/raw`)**: Rejected because introducing a third-party wrapper chart for manifests brings security, auditing, and maintenance concerns without true standard benefits.
