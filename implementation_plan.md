# Implementation Plan: Snowflake-Aware Cell Routing at NGINX Layer

## What We're Solving

```
                    ┌─────────────────────────────────────────────┐
Client              │          NGINX Ingress Controller           │
  │                 │                                             │
  │  POST /api/v1/  │  1. Intercept URL                          │
  │  payments/      │  2. Extract pi_<base64url> from path        │
  │  pi_ABC123/     │  3. Base64URL decode → 8 bytes → Long       │
  │  authorize  ──► │  4. (Long >> 12) & 31  → nodeId            │
  │                 │  5. Route → payment-edge-cell-<nodeId>      │
  │                 │     .payment-edge-cell-headless             │
  │                 └─────────────────────────────────────────────┘
  │                           ↓                ↓                ↓
  │                  cell-0 (pod 0)   cell-1 (pod 1)   cell-2 (pod 2)
```

Client hits the **same URL forever**. NGINX silently decodes the Snowflake ID and routes to the exact pod that owns that `paymentIntentId`. Zero application changes.

---

## Snowflake Bit Layout (from SnowflakeCore.kt)

```
Bit layout (64-bit Long, big-endian):
 [63─────22] timestamp (41 bits)
 [21─────17] regionId  (5 bits)
 [16─────12] nodeId    (5 bits)   ← NODE_SHIFT = 12, NODE_MASK = 31
 [11──────0] sequence  (12 bits)

Extraction:
  nodeId = (snowflakeId >> 12) & 0x1F
```

`publicPaymentIntentId = "pi_" + Base64URL(snowflakeLong_big_endian_8_bytes)`

---

## Critical Technical Clarification (from research)

> [!IMPORTANT]
> `nginx.ingress.kubernetes.io/configuration-snippet` runs **inside the `location` block** — it **cannot** override `proxy_pass`, which is already determined before that phase.
>
> The correct Lua hook for changing which upstream endpoint is selected is **`balancer_by_lua_block`**, which runs in the **balancer phase** (after upstream pool selection, before connecting to a pod).
>
> In ingress-nginx, this is configured at the **controller ConfigMap level** via `balancer-lua-shared-dicts` and a custom Lua file — not via Ingress annotations.

> [!WARNING]
> `configuration-snippet` is classified `Critical` risk in ingress-nginx v1.9.0+. In v1.12.0+, it is **blocked by default** even when `allowSnippetAnnotations: true` unless you also set `annotations-risk-level: Critical`.

---

## The Correct Implementation

We use two NGINX controller hooks together:

1. **`configuration-snippet`** (in the Ingress annotation): Runs Lua to **decode the paymentIntentId** from the URL and **write the target pod hostname into a shared dict** (by request ID).
2. **`balancer_by_lua`** (in controller ConfigMap): Reads the target from shared dict and **selects the exact endpoint** in the upstream pool.

**OR** — the simpler, fully working alternative for our single-tenant cluster:

Use `nginx.ingress.kubernetes.io/upstream-hash-by` with a **custom Nginx variable set by `co
nfiguration-snippet`** that is the extracted `nodeId` as a string. The hash of a constant string like `"0"`, `"1"`, `"2"` is always deterministic and will always map to the same slot. **But this still has the multi-replica consistency problem.**

**The cleanest approach that is 100% deterministic**: Route to the headless pod DNS directly using **`proxy_pass`** in a custom NGINX `server-snippet` / by adding a secondary Ingress per cell.

---

## Chosen Implementation: Two-Ingress Pattern (Clean, No Lua Risk)

Since our `paymentIntentId`'s nodeId maps to a known StatefulSet pod DNS name, we can use **regex Ingress path matching** + **separate backend Services per pod**. Each cell pod already has its own headless DNS:

```
payment-edge-cell-0.payment-edge-cell-headless.payment.svc.cluster.local
payment-edge-cell-1.payment-edge-cell-headless.payment.svc.cluster.local
```

**But decoding Base64URL to extract nodeId cannot be done with standard NGINX regex** — Lua is required. So the correct final approach is:

---

## ✅ Final Implementation: `configuration-snippet` + `balancer_by_lua` at Controller Level

### Files to Change

---

### 1. [MODIFY] [ingress-controller-values-azure.yaml](file:///Users/dogancaglar/IdeaProjects/ecommerce-platform-kotlin/infra/helm-values/ingress-controller-values-azure.yaml)

Enable snippets and set risk level. Add a global Lua balancer hook:

```yaml
controller:
  # ── Existing config ──
  metrics:
    enabled: true
    serviceMonitor:
      enabled: true
      namespace: ingress-nginx
      additionalLabels:
        release: prometheus-stack
  service:
    type: LoadBalancer
    annotations:
      service.beta.kubernetes.io/azure-load-balancer-health-probe-request-path: "/healthz"
    ports:
      http: 80
      https: 443
  resources:
    requests:
      cpu: 100m
      memory: 400Mi
    limits:
      memory: 400Mi
  admissionWebhooks:
    enabled: false

  # ── NEW: Enable Lua snippet annotations ──
  allowSnippetAnnotations: true

  config:
    # Required to allow configuration-snippet (rated Critical by ingress-nginx)
    annotations-risk-level: "Critical"
    # Shared dict for the cell-routing Lua state
    lua-shared-dicts: "cell_routing: 1m"
```

---

### 2. [MODIFY] [values.yaml (payment-edge-cell azure)](file:///Users/dogancaglar/IdeaProjects/ecommerce-platform-kotlin/charts/payment-edge-cell/azure/values.yaml)

Add a `configuration-snippet` annotation that:
1. Reads the `paymentIntentId` from the URL path
2. Decodes Base64URL → 8-byte big-endian Long
3. Extracts `nodeId = (long >> 12) & 31`
4. Stores it in NGINX shared dict (keyed by `ngx.var.request_id`)
5. Sets `$target_upstream` variable

```yaml
ingress:
  enabled: true
  annotations:
    nginx.ingress.kubernetes.io/use-regex: "true"
    nginx.ingress.kubernetes.io/configuration-snippet: |
      set $cell_target "";
      access_by_lua_block {
        local uri = ngx.var.uri
        -- Match /api/v1/payments/pi_<base64url>/authorize (and other sub-paths)
        local encoded = string.match(uri, "^/api/v1/payments/pi_([A-Za-z0-9_%-]+)")
        if encoded then
          -- Base64URL → standard Base64
          encoded = string.gsub(encoded, "-", "+")
          encoded = string.gsub(encoded, "_", "/")
          local pad = 4 - (#encoded % 4)
          if pad < 4 then encoded = encoded .. string.rep("=", pad) end

          -- Decode to raw bytes (ngx.decode_base64 is available in OpenResty/NGINX)
          local raw = ngx.decode_base64(encoded)
          if raw and #raw == 8 then
            -- Parse 8-byte big-endian signed long
            local hi =  string.byte(raw,1) * 0x1000000
                      + string.byte(raw,2) * 0x10000
                      + string.byte(raw,3) * 0x100
                      + string.byte(raw,4)
            local lo =  string.byte(raw,5) * 0x1000000
                      + string.byte(raw,6) * 0x10000
                      + string.byte(raw,7) * 0x100
                      + string.byte(raw,8)

            -- nodeId = (lo >> 12) & 31
            -- sequence is 12 bits (lo), node is next 5 bits
            local node_id = math.floor(lo / 4096) % 32

            ngx.var.cell_target = "payment-edge-cell-" .. node_id
                                .. ".payment-edge-cell-headless.payment.svc.cluster.local"
            ngx.log(ngx.INFO, "[cell-router] paymentIntentId=pi_", encoded,
                               " nodeId=", node_id, " target=", ngx.var.cell_target)
          end
        end
      }
  paths:
    - path: /api/v1/payments/pi_
      pathType: Prefix
```

> [!IMPORTANT]
> `ngx.decode_base64` is available in the ingress-nginx controller since it ships with OpenResty's Lua runtime. No additional modules required.

---

### 3. [MODIFY] [ingress.yaml (payment-edge-cell template)](file:///Users/dogancaglar/IdeaProjects/ecommerce-platform-kotlin/charts/payment-edge-cell/templates/ingress.yaml)

No structural changes needed — the annotation passthrough already works via `{{- toYaml .Values.ingress.annotations | nindent 4 }}`.

---

## Deployment Steps

```bash
# 1. Upgrade ingress controller with new config
helm upgrade ingress-nginx ingress-nginx/ingress-nginx \
  -n ingress-nginx \
  -f infra/helm-values/nginx-ingress-controller-values-azure.yaml

# 2. Upgrade payment-edge-cell chart
helm upgrade payment-edge-cell charts/payment-edge-cell \
  -n payment \
  -f charts/payment-edge-cell/azure/values.yaml

# 3. Verify Lua routing in NGINX logs
kubectl logs -n ingress-nginx deploy/ingress-nginx-controller | grep "cell-router"
```

---

## Verification Plan

### Before test — confirm routing is live
```bash
# Grab a real paymentIntentId from a create response, then:
kubectl logs -n ingress-nginx deploy/ingress-nginx-controller --tail=50 | grep cell-router
# Expected: [cell-router] paymentIntentId=pi_XXXX nodeId=1 target=payment-edge-cell-1.payment-edge-cell-headless...
```

### k6 stress test
```bash
k6 run -e PROFILE=stress load-tests/k6-payment-flow-azure.js
```

### Success criteria
| Metric | Before | After (Target) |
|---|---|---|
| HTTP 500 rate on `/authorize` | ~77% at 3 pods | **0%** |
| p99 latency | High (retries) | Baseline |
| `cell-router` log entries | None | One per `/authorize` call |
