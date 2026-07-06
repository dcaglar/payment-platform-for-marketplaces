# 📘 Azure Operational Runbook & System Access Guide

This runbook contains all the instructions, commands, and procedures required to connect to, access, and manage the Merchant-of-Record (MoR) system on Azure.

---

## 1. Connecting to the Azure Cluster

Run these commands in your terminal to set your Kubernetes context to the Azure load test environment:

```bash
# 1. Log in to Azure
az login

# 2. Set the subscription
az account set --subscription "7ff93b69-058b-4fee-8dc3-933e9d0d1b86"

# 3. Retrieve kubectl credentials (overwrites local config)
az aks get-credentials --resource-group rg-payment-platform-loadtest --name aks-payment-loadtest --overwrite-existing
```

---

## 2. Public Web UIs & API Endpoints

These are the public external endpoints currently provisioned in Azure. 

*   **📊 Grafana Load Test Dashboard**: [http://4.175.208.51](http://4.175.208.51)
*   **🔥 Prometheus UI**: [http://20.31.203.163:9090](http://20.31.203.163:9090)
*   **🔑 Keycloak Authorization Server**: [http://4.175.112.183:8080](http://4.175.112.183:8080)
*   **💳 Payment Service API (k6 Target)**: `http://20.8.218.64`

---

## 3. How to Find These IPs Dynamically

Azure assigns dynamic IPs to public load balancers. If the IPs change (for example, after a complete redeploy), you can find the new ones instantly by running these commands:

### A. List All Public Services & IPs
The easiest way to see all public IPs is to list services across all namespaces and look for the `LoadBalancer` type:
```bash
kubectl get svc -A | grep LoadBalancer
```
*Output will show the `EXTERNAL-IP` column, which contains the public IP.*

### B. Command to Find the Payment Service API IP
The payment API is routed through the NGINX Ingress Controller. Run this command to fetch its exact public IP:
```bash
kubectl get ingress -n payment payment-edge-cell -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

### C. Command to Find the Keycloak IP
To fetch the public IP specifically for Keycloak:
```bash
kubectl get svc -n payment keycloak -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

### D. Command to Find the Grafana IP
To fetch the public IP specifically for Grafana:
```bash
kubectl get svc -n monitoring prometheus-stack-grafana -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```
To fetch defaul username/password
```bash
 kubectl get secret --namespace monitoring prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 --decode ; echo
```


### E. Command to Find the Prometheus IP
To fetch the public IP specifically for Prometheus:
```bash
kubectl get svc -n monitoring prometheus-stack-kube-prom-prometheus -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
```

---

## 4. Keycloak Script Adaptations

The Keycloak helper scripts are configured to dynamically run the `kubectl` queries above so you do not have to update them manually.

*   **To provision Keycloak on Azure**: `bash ./keycloak/provision-keycloak-azure.sh`
*   **To get a valid load test token**: `bash ./keycloak/get-token-azure.sh`

---

## 5. Accessing Databases & Kafka (Internal Services)

Databases and message brokers are kept private inside the cluster. Run these commands to port-forward them to your local machine.

> [!NOTE]
> Keep the terminal window open while you are connected. Press `Ctrl+C` to close the connection.

### 🗄️ Central DB (PostgreSQL)
Run this command, then connect your SQL Client (e.g. DBeaver) to `localhost:5432` (Database: `central-db`):
```bash
kubectl port-forward -n payment svc/central-db-postgresql 5432:5432
```

### 🗄️ Edge DB (SQLite/Postgres inside payment-edge-cell pods)
Run this command, then connect your SQL client to `localhost:5433` (Database: `edge-db`):
```bash
kubectl port-forward -n payment statefulset/payment-edge-cell 5433:5432
```

### 🐙 Kafka Broker
Run this command, then connect your local Kafka tools to `localhost:9092`:
```bash
kubectl port-forward -n payment svc/kafka 9092:9092
```

###  fastest way to apply a values.yaml change directly to Kubernetes without waiting for a full script redeploy or building any images is to run a targeted helm upgrade, followed by a rollout restart (if needed).
```bash
helm dependency update charts/payment-consumers
helm secrets upgrade --install payment-consumers charts/payment-consumers \
-n payment --create-namespace \
-f charts/payment-consumers/values.yaml \
-f charts/payment-consumers/local/values.yaml \
-f secrets://central-db-sops-secrets.yaml
```
# Force the Pod to Restart (If necessary)
   When Helm runs, it updates the ConfigMap. However, Kubernetes does not automatically restart a pod just because a ConfigMap changed (unless you use a special checksum annotation in your Helm template).

To force Kubernetes to gracefully spin up a new pod with the new injected values, and then terminate the old pod (Zero Downtime Rolling Update), run this instantly after the Helm command:
```bash
kubectl rollout restart statefulset/payment-consumers -n payment
```
## 6. Testing the Payment API

Once your Azure infrastructure is deployed, you can test the public Payment API using standard curl commands. 

### Step 1: Generate a valid JWT Token
You must fetch a valid token from your Azure Keycloak instance using the provided helper script:
```bash
# This fetches the token and saves it to ./keycloak/output/jwt/payment-service.token
bash ./keycloak/get-token-azure.sh
```

### Step 2: Extract the Public API IP
Store the NGINX Ingress public IP into an environment variable:
```bash
export API_IP=$(kubectl get ingress -n payment payment-edge-cell -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "Public API IP: $API_IP"
```

### Step 3: Create a Payment Intent
Execute the curl request against the public IP, injecting the token from the file:
```bash
IDEMPOTENCY_KEY=$(printf '%08x-%04x-7%03x-8%03x-%04x%08x' $((RANDOM*RANDOM)) $((RANDOM)) $((RANDOM%4096)) $((RANDOM%4096)) $((RANDOM)) $((RANDOM*RANDOM)))
echo "Using Idempotency-Key=$IDEMPOTENCY_KEY"
curl -i -X POST http://51.105.254.202/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat ./keycloak/output/jwt/payment-service.token)" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{
    "orderId": "ORDER-1450",
    "buyerId": "BUYER-1450",
    "merchantAccount": "MARKETPLACE-5",
    "processingModel": "MARKETPLACE",
    "totalAmount": { "quantity": 3000, "currency": "EUR" },
    "splits": [
      { "type": "BalanceAccount", "account": "SELLER-5-1", "amount": { "quantity": 1400, "currency": "EUR" }},
      { "type": "Commission", "amount": { "quantity": 100, "currency": "EUR" }},
      { "type": "BalanceAccount", "account": "SELLER-5-2", "amount": { "quantity": 1400, "currency": "EUR" }},
      { "type": "Commission", "amount": { "quantity": 100, "currency": "EUR" }}
    ]
  }'
```

### Step 4: Authorize the Payment Intent
Copy the `paymentIntentId` returned from Step 3 (e.g., `pi_AcqzYyHCcAA`) and authorize it:
```bash
PAYMENT_INTENT_ID="<paste-your-payment-intent-id-here>"

curl -i -X POST "http://51.105.254.202/api/v1/payments/pi_Ar6RoATCAAA/authorize" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $(cat ./keycloak/output/jwt/payment-service.token)" \
  -d '{}'
```



