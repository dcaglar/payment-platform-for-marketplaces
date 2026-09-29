# OrbStack Services Connectivity Guide

With OrbStack, there is no need for `kubectl port-forward` or messy proxy tunnels. You can natively access Kubernetes `ClusterIP`, `NodePort`, `LoadBalancer`, and internal DNS names (`.svc.cluster.local`) directly from your Mac's host OS and browser.

Here is your standardized cheat sheet for reaching all infrastructure components and checking their essentials locally.

---

## 📊 1. Observability (Prometheus & Grafana)

### Grafana
- **URL**: [http://localhost:32000](http://localhost:32000)
- **Default Credentials**: `admin` / `admin`

### Prometheus
- **URL**: [http://prometheus-stack-kube-prom-prometheus.monitoring.svc.cluster.local:9090](http://prometheus-stack-kube-prom-prometheus.monitoring.svc.cluster.local:9090)
- **Usage**: Use this to raw-query metrics like `kafka_consumergroup_lag` or `outbox_dispatched_total`.

### Alertmanager
- **URL**: [http://prometheus-stack-kube-prom-alertmanager.monitoring.svc.cluster.local:9093](http://prometheus-stack-kube-prom-alertmanager.monitoring.svc.cluster.local:9093)

---

## 🗄️ 2. Databases

Passwords live in the sops-encrypted files at the repo root. `sops -d --extract '["KEY"]'` decrypts a single key
and passes it straight to `psql`, so the password is never printed (run from the repo root).

### Central DB (PostgreSQL)
- **Host**: `central-db-postgresql.payment.svc.cluster.local`
- **Port**: `5432`
- **Database**: `central-db`
- **User**: `postgres` (password key `CENTRAL_DB_POSTGRES_PASSWORD` in `central-db-sops-secrets.yaml`)
- **CLI Connection**:
  ```bash
  PGPASSWORD="$(sops -d --extract '["CENTRAL_DB_POSTGRES_PASSWORD"]' central-db-sops-secrets.yaml)" \
    psql -h central-db-postgresql.payment.svc.cluster.local -p 5432 -U postgres -d central-db
  ```

### Edge DB (PostgreSQL)
The Edge DB is a `postgres:15` sidecar (`edge-db` container) inside each `payment-edge-cell-N` pod.
- **Host**: `payment-edge-cell.payment.svc.cluster.local` (one specific cell: `payment-edge-cell-N.payment-edge-cell-headless.payment.svc.cluster.local`)
- **Port**: `5432`
- **Database**: `edge-db`
- **User**: `postgres` (password key `EDGE_DB_POSTGRES_PASSWORD` in `edge-cell-sops-secrets.yaml`).
  For day-to-day data checks you can also use the duty user (`EDGE_DB_DUTY_USERNAME` / `EDGE_DB_DUTY_PASSWORD`).
- **CLI Connection**:
  ```bash
  PGPASSWORD="$(sops -d --extract '["EDGE_DB_POSTGRES_PASSWORD"]' edge-cell-sops-secrets.yaml)" \
    psql -h payment-edge-cell.payment.svc.cluster.local -p 5432 -U postgres -d edge-db
  ```
---

## 📨 3. Kafka (Event Streaming)

- **Broker Address**: `kafka.payment.svc.cluster.local:9092`

### Checking Essentials (Run natively from your Mac terminal)

**List all topics:**
```bash
kafka-topics --bootstrap-server kafka.payment.svc.cluster.local:9092 --list
```

**Describe a specific topic (e.g., DLQs):**
```bash
kafka-topics --bootstrap-server kafka.payment.svc.cluster.local:9092 \
  --topic payment_order_capture_request_queue_topic --describe
```

**List consumer groups:**
```bash
kafka-consumer-groups --bootstrap-server kafka.payment.svc.cluster.local:9092 --list
```

**Check Consumer Lag (Crucial for monitoring throughput):**
```bash
kafka-consumer-groups --bootstrap-server kafka.payment.svc.cluster.local:9092 \
  --group payment-order-capture-executor-consumer-group --describe
```

**Watch DLQ Messages live (without advancing offsets):**
```bash
kafka-console-consumer \
  --bootstrap-server kafka.payment.svc.cluster.local:9092 \
  --topic payment_order_psp_call_requested_topic.DLQ \
  --property print.headers=true \
  --property print.timestamp=true
```

---

## 🔐 4. Keycloak (Identity & Access)

- **UI URL**: [http://127.0.0.1:32080](http://127.0.0.1:32080) (OrbStack automatically exposes NodePorts)
  - *Alternative internal DNS*: [http://keycloak.payment.svc.cluster.local:8080](http://keycloak.payment.svc.cluster.local:8080)

**Getting an Access Token via CLI:**
```bash
./keycloak/get-token.sh
```

---

## 🚀 5. Redis (Caching & Rate Limiting)

- **Host**: `redis-master.payment.svc.cluster.local`
- **Port**: `6379`

### Checking Essentials

**Connect via CLI:**
```bash
redis-cli -h redis-master.payment.svc.cluster.local -p 6379
```

**Useful Commands (run inside `redis-cli`):**
```bash
# See cluster memory usage and engine stats
INFO memory

# Monitor commands in real-time (great for debugging rate-limiters/cache misses)
MONITOR

# See all keys (Do not run in production, but safe for local debugging)
KEYS *

# Flush the cache (drop all idempotency keys / JWT caches)
FLUSHALL
```

---

## 🌐 6. API Gateway / Ingress

- **Base API URL**: [http://localhost](http://localhost)

**Verify Routing:**
```bash
curl -I http://localhost/api/v1/payments
```
