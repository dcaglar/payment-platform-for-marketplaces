# M1 MacBook Pro → Always-On Bare-Metal Kubernetes Server — Design

Oct 14, 2026 · @Dogan Caglar · revised Oct 15, 2026 against the charts, values files and scripts in this repo

This is the **what and why**. The **how**, step by step with checks and failure handling, is the runbook: [`m1-runbook.md`](./m1-runbook.md). Diagram of everything: [`m1alwayson-infra.excalidraw`](./m1alwayson-infra.excalidraw) (excalidraw.com → Open, or the Excalidraw plugin in IntelliJ / VS Code).

## 1. Goals and scope

Turn the retired 14-inch MacBook Pro (M1 Pro, 8-core CPU, 16 GB RAM, 512 GB SSD, built-in display dead) into an always-on, internet-reachable Kubernetes node running the payment platform's existing Helm topology, unchanged from what already runs locally in OrbStack (`docs/how-to-start.md`, sections 0 and 1).

Success criteria:

- [ ] Boots headless (lid closed, no monitor) and rejoins the LAN on its own after a power cut
- [ ] The full topology (Kafka, Postgres, Redis, Keycloak, four JVM services, monitoring) starts from a cold boot with no manual steps
- [ ] The checkout page, the back office and the API are reachable from the public internet over TLS, with no port forwarded on the router
- [ ] SSH and the Kubernetes API stay private, reachable only over Tailscale, never directly from the internet
- [ ] A full rebuild from git plus backups finishes in under an hour

The machine was Dogan's personal laptop. Before any server work: full backup, sign out of iMessage/FaceTime/App Store/iCloud, then a clean erase-and-reinstall of macOS (decided). Runbook Phase 0.

## 2. Architecture at a glance

### 2.1 How a public request reaches the Mac

No connection is ever opened *into* the Mac.

1. Cloudflare is the authoritative DNS for `dc4pay.com` (registered there; nothing more to do)
2. Each public hostname is a proxied CNAME to the tunnel (`<hostname> → <tunnel-id>.cfargotunnel.com`). Public resolvers answer with Cloudflare's IPs; the home IP never appears
3. The visitor connects to Cloudflare's edge, which terminates TLS with its free Universal certificate (covers `dc4pay.com` and one level of subdomain, so every name below works with no certificate on our side)
4. Cloudflare pushes the request down the connection `cloudflared` already holds open outbound
5. `cloudflared` matches the hostname against the tunnel's ingress rules and forwards plain HTTP to ingress-nginx, keeping the `Host` header

**One layer-7 front door.** Every public hostname goes through the tunnel to the same target, ingress-nginx, which picks the service by `Host` header and path. Every HTTP service is `ClusterIP` with its own `Ingress` object; nothing is exposed as a `LoadBalancer` service on the server (Keycloak is one today — an OrbStack convenience for the developer's Mac; `prod` turns it into `ClusterIP` + the Bitnami chart's ingress). This is the standard shape: one place for forwarded headers, access logs, request metrics (ingress-nginx is already scraped by the monitoring stack), rate limits and per-path restrictions; adding a service later is one `Ingress`, not a new tunnel rule.

A hostname lives in three places that must agree: the DNS CNAME, the tunnel's ingress rule (hostname → ingress-nginx), and the `host` of the service's `Ingress`. The API keeps its existing **host-less, path-based** rules exactly as in `docs/how-to-start.md` (`/api/v1/payments` → edge cell with the Snowflake cell router; `/api/v1/balances|transactions|txs|accounts` → consumers); the others get host rules. Host-specific rules win over host-less ones, and the Lua cell router is an annotation on the edge-cell ingress only, so Keycloak and Grafana traffic never meet it.

| Hostname | Ingress object (`host`) | Backend | Access |
| --- | --- | --- | --- |
| `api.dc4pay.com` | `payment-edge-cell`, `payment-consumers` (host-less, as today) | edge cell / consumers | public, token-protected |
| `auth.dc4pay.com` | Keycloak chart ingress, `hostname: auth.dc4pay.com`, path `/` | `keycloak:8080` | public; `/admin` behind Cloudflare Access |
| `checkout.dc4pay.com` | checkout-demo chart ingress (new) | checkout-demo | public |
| `backoffice.dc4pay.com` | mor-backoffice chart ingress (new) | mor-backoffice | public, Keycloak login |
| `grafana.dc4pay.com` | kube-prometheus-stack Grafana ingress | Grafana | Cloudflare Access + Grafana login |
| `argocd.dc4pay.com` (optional; else port-forward over Tailscale) | Argo CD chart ingress | Argo CD UI | Cloudflare Access + Argo login |
| SSH, Kubernetes API, Prometheus, Alertmanager | — | — | Tailscale only; no public DNS record |

Because ingress-nginx sits between Cloudflare and Keycloak, the `prod` ingress-nginx values need `use-forwarded-headers: "true"`, so Cloudflare's `X-Forwarded-Proto: https` reaches Keycloak (`KC_PROXY=edge` trusts it). Without it Keycloak builds `http://` redirect URLs and logins loop. The tunnel has a single rule set: every hostname → `ingress-nginx-controller.ingress-controller.svc.cluster.local:80`.

`cloudflared` runs **inside the cluster** (official image, one Deployment, tunnel token in a SOPS secret) rather than `brew install cloudflared` on macOS: it starts and restarts with the cluster, needs no LoadBalancer-IP or `localhost` assumption, and is one more release for Argo to manage. The tunnel is remotely managed, so its ingress rules live at Cloudflare (and in Terraform, §3.6) instead of in a `config.yml` on the Mac.

### 2.2 How you reach the Mac

Tailscale: a private WireGuard network between your devices. The server runs `tailscaled` as a launchd daemon (from boot, before login), with key expiry disabled, tagged `tag:server`; your laptop runs the Tailscale app. SSH, `kubectl`, `helm secrets`, `sops -i`, port-forwards to Prometheus/Argo all go over it. Nothing administrative has a public DNS record.

| You are | Reach the server by | Reach the cluster's services by |
| --- | --- | --- |
| at home, before Tailscale | `m1-server.local` (Bonjour; SSH, Screen Sharing) | SSH in; `kubectl` / `curl` on the server itself |
| anywhere, after Tailscale | `m1-server` (MagicDNS, no router change) | same, or `ssh -L` port-forward, or the public hostnames once Cloudflare is up |
| the public | never | `*.dc4pay.com` through Cloudflare only |

OrbStack's `LoadBalancer` IPs (the ingress IP how-to-start uses) exist only on the Mac running OrbStack: the server's cluster has no LAN address, by design. Inside = SSH/Tailscale, outside = Cloudflare.

## 3. Decisions

### 3.1 Runtime: OrbStack first, Asahi + k3s later

|  | OrbStack (macOS) | Asahi Linux + k3s |
| --- | --- | --- |
| Setup effort | None — already configured, charts deploy as-is | A clean OS install, everything re-provisioned from git |
| RAM overhead | A Linux VM layer sits under every pod | None — k3s runs natively on bare metal |
| Familiarity | Same environment already used for local development | New: systemd, native Linux networking |
| Risk | Lowest, already proven | Higher short-term, removes the VM tax permanently |

Stay on OrbStack for the first deployment. Revisit Asahi + k3s once the platform is stable, to reclaim the RAM the VM layer costs on a 16 GB machine. OrbStack caps its VM memory by default (check the value in Settings → Resources on the machine); set it to **14 GB** (`orb config set memory_mib 14336`), leaving 2 GB for macOS; §4.1 says why. CPU needs no change. OrbStack is a user application, not a system daemon: it runs under the auto-login service user as a Login Item, and the cluster is gone if that user logs out.

### 3.2 A `prod` track next to `local`; `azure` is out of date

The repo keeps one deployment track per environment: the same charts, differing only in values files and Spring profile. **`local` is the reference**: the last track deployed and tested (`docs/how-to-start.md`). The `azure` track exists but does not deploy from the current branch (its own audit in `adr-001-azure-infrastructure.md` lists why); copy nothing from it. The server is a new track, `prod`, modelled on `local`. `local` stays exactly as it is.

| | local (developer's Mac, OrbStack) | prod (the M1 server, OrbStack) |
| --- | --- | --- |
| Spring profile (`spring.profile` in the chart values → `SPRING_PROFILES_ACTIVE`) | `local` → `application-local.yml` | `prod` → `application-prod.yml` (copy of `application-local.yml` to start; the Keycloak JWKS URL in §3.4 is the first difference) |
| Our charts | `values.yaml` + `charts/<chart>/local/values.yaml` | `values.yaml` + `charts/<chart>/prod/values.yaml` |
| Third-party charts | `infra/helm-values/<component>-values-local.yaml` | `infra/helm-values/<component>-values-prod.yaml` |
| Secrets | `edge-cell-sops-secrets.yaml`, `central-db-sops-secrets.yaml` | the same two plus `keycloak-`, `cloudflared-`, `monitoring-sops-secrets.yaml` (§3.3) |
| Deploying | `deploy-*-local.sh` scripts | no deploy scripts: Argo CD Application manifests under `infra/argocd/prod/` + one `bootstrap-prod.sh` |
| kube context | `orbstack` | `m1-prod` (renamed once on the server) |

What goes into the `prod` values and nowhere else: the Keycloak public hostname and the issuer/JWKS URLs, `use-forwarded-headers` on ingress-nginx, the Ingress hosts, the memory limits for the 16 GB box, and the git-sha image tag that CI bumps. Everything the tracks share stays in the base `values.yaml`.

**Rule: nothing that runs against the server has `local` in its name, and nothing with `local` in its name is ever run against the server.** Shared between the tracks are *artifacts* only: chart templates and base `values.yaml`, Dockerfiles and images, the realm JSON files, the source. Everything that *acts* on a cluster — scripts, values overlays, Argo manifests, kube context — exists once per track.

| Today | Problem | `prod` |
| --- | --- | --- |
| `build-and-push-local.sh`, `build-all-payment-platform-images-and-push.sh` | named `local`, but they produce the images every track pulls | renamed `build-and-push.sh` / `build-all-images.sh` (merge with the CI `build-and-push.sh`). **One registry, one build**: `dcaglar1987/<service>` on Docker Hub serves both tracks; no second registry, no prod-specific image. Only the tag differs: `latest` for local, the git sha for prod (§3.5) |
| `deploy-all-external-infra-local.sh`, `deploy-payment-platform-services-local.sh`, `deploy-monitoring-stack-local.sh`, `delete-all-local.sh` | the local deploy path | untouched. `prod` deploys through Argo; `infra/scripts/bootstrap-prod.sh` installs Argo and the root app and is the only script that targets `m1-prod` |
| `deploy-external-infra-local.sh <name>` (generic installer, used for the exporters) | shared helper | not used on prod; the exporters are Argo Applications |
| `keycloak/setup-keycloak.sh` | shared, `KEYCLOAK_URL` from env, admin password hard-coded | `keycloak/setup-keycloak-prod.sh`: Keycloak URL, admin password and the three staff passwords from `keycloak-sops-secrets.yaml`; same two realm JSON files; applies the prod-only realm settings (§3.4). The local script stays as it is |
| `keycloak/get-access-token.sh` | shared, in-cluster default URL | fine for local; on the server run it over Tailscale with `KEYCLOAK_URL` set; `-prod` sibling only if it grows prod-only logic |
| `charts/<chart>/local/values.yaml`, `infra/helm-values/*-values-local.yaml` | local overlays | never referenced by anything prod; `prod/values.yaml` and `*-values-prod.yaml` are written from scratch (copying lines is fine, referencing the file is not) |
| kube context `orbstack` on both machines | a `*-local.sh` run on the server would pass its own guard | server context renamed `m1-prod`; `*-local.sh` require `orbstack`, `bootstrap-prod.sh` requires `m1-prod` |

Check before the first deployment: `grep -rl local infra/argocd/prod infra/scripts/bootstrap-prod.sh keycloak/setup-keycloak-prod.sh charts/*/prod infra/helm-values/*-prod.yaml` returns nothing.

### 3.3 Secrets: the SOPS standard

**Today.** `.sops.yaml` has one rule (`encrypted_regex: '.*'`, one age recipient): every value in a matching file is encrypted, key names stay readable. Two flat `KEY: value` files in the repo root: `edge-cell-sops-secrets.yaml` (Stripe key, the edge-db users, PostHog token) and `central-db-sops-secrets.yaml` (the central-db users, PostHog token again). Editing is `sops -i <file>`: decrypted in the editor, re-encrypted on save; only the holder of the age private key can do it (the Mac's sops key file; `SOPS_AGE_KEY` in GitHub). The deploy scripts pass each file with `helm secrets upgrade --install … -f secrets://<file>`; the plugin decrypts in memory as one more values file; our templates read `.Values.<KEY>` into Kubernetes `Secret`s (`central-db-credentials`, `edge-cell-credentials`, `payment-central-relay-job-credentials`, `central-hub-credentials`); pods use `secretKeyRef` / `envFrom`.

**Not in this system today:** Keycloak's admin and database passwords (`keycloak-values-local.yaml`, plain), the admin password hard-coded in `setup-keycloak.sh`, the merchant client secrets and user passwords (realm JSON), and, new on the server, the Cloudflare tunnel token, Grafana's admin password, the back office's `SESSION_SECRET`.

**The standard for `prod`:**

1. Every credential lives in a SOPS file edited with `sops -i`; never in a values file, a script or a JSON. Plain-text defaults remain acceptable only in `local`
2. Third-party charts take a `secrets://` file like our own charts; the file uses the chart's own key paths: `keycloak-sops-secrets.yaml` (`auth.adminPassword`, `postgresql.auth.password`, plus our `staffPasswords`), `monitoring-sops-secrets.yaml` (`grafana.adminPassword`), `cloudflared-sops-secrets.yaml` (`tunnel.token`), `backoffice-sops-secrets.yaml` (`sessionSecret`). No `existingSecret` plumbing
3. Scripts read from the same files (`sops -d --extract '["auth"]["adminPassword"]' keycloak-sops-secrets.yaml`)
4. Realm seed: merchant client secrets and the `marketplace-N` / `seller-N-M` passwords **stay public demo accounts** (decided Oct 15, 2026): Stripe is test mode and they reach only demo data; `merchants-seed.json` is unchanged. The three staff passwords (`support-ops`, `finance-ops`, `backoffice-admin`) are rotated into `keycloak-sops-secrets.yaml`: `merchant:all`, `ledger:read` and above all `account:write` (creates merchants in central-db) are not demo rights. Consequence of public merchant credentials: anyone can create payments, so a Cloudflare rate-limit rule on `POST api.dc4pay.com/api/v1/payments` guards the disk
5. Housekeeping: the PostHog token is in both existing files; keep it in one
6. The server gets its **own age key**, added as a second recipient in `.sops.yaml` (`sops updatekeys` on every file from the laptop, which holds the first key); the two keys rotate independently and the laptop's private key never leaves the laptop. Argo's repo-server runs the `helm-secrets` sidecar with that key mounted, so Applications reference `secrets://…` values files exactly as the scripts do

### 3.4 Keycloak gets a public name — and that changes every token

**Today** (`infra/helm-values/keycloak-values-local.yaml`, Bitnami chart 20.0.0, Keycloak 23.0.7): one replica with a bundled Postgres, `service.type: LoadBalancer` on 8080, no ingress; `KC_HOSTNAME=keycloak.payment.svc.cluster.local`, `KC_HOSTNAME_PORT=8080`, `KC_HOSTNAME_STRICT=false`, `KC_PROXY=edge`. Keycloak puts that name into the `iss` claim of every token: `http://keycloak.payment.svc.cluster.local:8080/realms/ecommerce-platform`. payment-service and payment-consumers are plain Spring resource servers (`SecurityConfig` in each: `oauth2ResourceServer().jwt()`, realm roles → authorities) whose only Keycloak setting is `issuer-uri: ${KEYCLOAK_ISSUER_URL}`, set in the chart ConfigMaps to that same string. No `jwk-set-uri` anywhere. Spring resolves the issuer lazily, on the first request carrying a JWT. People open the login page at that name, which resolves only on the developer's Mac (OrbStack); `setup-keycloak.sh` and `get-access-token.sh` default to the same URL.

**What `keycloak/setup-keycloak.sh` creates** (the only writer of Keycloak state; idempotent): logs in to the master realm as `admin`/`adminpassword` (hard-coded in the script), creates or partial-imports realm `ecommerce-platform` from `realm/ecommerce-platform.json`, partial-imports `realm/merchants-seed.json`, deletes the clients and the role of an older design. Result: 7 permission roles (`payment:read`, `payment:write`, `balance:read`, `transaction:read`, `ledger:read`, `account:write`, `merchant:all`) and 5 composites (`MERCHANT`, `SELLER`, `SUPPORT`, `FINANCE` = `SUPPORT` + `ledger:read`, `ADMIN` = `FINANCE` + `account:write`); one public client `backoffice-ui` (authorization code + PKCE S256, password grant on, redirects `http://localhost:*`, mappers copying user attributes `merchant_id` / `seller_id` into the token); 5 confidential clients `merchant-api-MARKETPLACE-N` (client credentials, 10-hour tokens, `merchant_id` hard-coded by a mapper, service-account user with `MERCHANT`); users `marketplace-N` (`MERCHANT`), 50 `seller-N-M` (`SELLER`), `support-ops` / `finance-ops` / `backoffice-admin`. Realm settings: `sslRequired: none`, `accessTokenLifespan: 300`, everything else at Keycloak defaults (brute-force protection off). The authorization model is hostname-independent and needs no change; realm settings and secrets do.

**The mechanism already exists in the repo:** the e2e `PlatformStack` runs Keycloak with `KC_HOSTNAME_URL=http://keycloak:8080`, `KC_HOSTNAME_STRICT=false`, `KC_HOSTNAME_STRICT_BACKCHANNEL=false` and gives the services the matching issuer. The server does the same with `https://auth.dc4pay.com`:

| Where | Today | `prod` |
| --- | --- | --- |
| Keycloak values | `KC_HOSTNAME` + `KC_HOSTNAME_PORT` | `KC_HOSTNAME_URL=https://auth.dc4pay.com`, `KC_HOSTNAME_ADMIN_URL` the same, `KC_HOSTNAME_STRICT_BACKCHANNEL=false` (explicit), keep `KC_PROXY=edge`. Token and JWKS endpoints keep answering on the in-cluster name; login page and `iss` use the public one |
| Keycloak values | `service.type: LoadBalancer`, `ingress.enabled: false` | `service.type: ClusterIP`, `ingress.enabled: true`, `ingress.hostname: auth.dc4pay.com` |
| Keycloak values | admin and Postgres passwords in plain text | `keycloak-sops-secrets.yaml` as `secrets://` values file |
| Realm settings | `sslRequired: none`, brute force off | applied by `setup-keycloak-prod.sh` through the admin API after the import (so the shared JSON stays shared): `sslRequired: external`, `bruteForceProtected: true` |
| Realm, client `backoffice-ui` | redirect URIs `http://localhost:*`; password grant on | add `https://backoffice.dc4pay.com/*` to the JSON (harmless for local); password grant stays on (demo credentials are public anyway; scripts keep working) |
| Realm staff users | passwords in git | set by `setup-keycloak-prod.sh` from SOPS after the import |
| `payment-edge-cell`, `payment-consumers` ConfigMaps | `KEYCLOAK_ISSUER_URL` in-cluster | `KEYCLOAK_ISSUER_URL=https://auth.dc4pay.com/realms/ecommerce-platform` **plus** `KEYCLOAK_JWK_SET_URL=http://keycloak.payment.svc.cluster.local:8080/realms/ecommerce-platform/protocol/openid-connect/certs`, mapped to `jwk-set-uri` in `application-prod.yml`. With both set, Spring checks the `iss` text against the public name and fetches keys inside the cluster: validating a token never goes through Cloudflare |
| `payment-edge-workers` ConfigMap | `KEYCLOAK_ISSUER_URL` set, but the app has no Spring Security | drop the variable |
| mor-backoffice, checkout-demo | find Keycloak as `keycloak.payment.svc.cluster.local` | `KEYCLOAK_URL` stays in-cluster for server-side calls; `PUBLIC_URL` public |

To check on the machine: whether the Bitnami chart runs Keycloak in production mode (`production: true` → `kc.sh start`, `hostname-strict-https` on) or dev mode; the values don't set it.

### 3.5 GitOps with Argo CD; images pinned by git sha

|  | Self-hosted runner | GitOps pull (Argo CD) |
| --- | --- | --- |
| Needs inbound access to the cluster | Yes — the runner holds full cluster credentials | No — the controller runs inside the cluster and pulls |
| CI's job | Build, test, `helm upgrade` directly | Build, test, push the image, bump a tag in git |
| Audit trail | Only in CI logs | Full history in git, with drift detection |

**Argo CD** (decided Oct 15, 2026; chosen over Flux to learn it; the SOPS question is covered by the `helm-secrets` Argo integration, §3.3):

1. **Images are pinned by git sha; `latest` never reaches prod.** On every push to `main`, after the tests, one CI job builds the images once and pushes each twice-tagged: `dcaglar1987/<service>:<git-sha>` and `:latest`. `latest` is for `local` (unchanged: pull, `rollout restart`). The same job edits `image.tag` in `charts/<chart>/prod/values.yaml` to the sha and commits it (`[skip ci]`) — only for the charts whose code changed, otherwise every push would restart every pod. That commit is the deployment: Argo rolls the pods; rollback is syncing to the previous commit, which brings back the previous sha, something a moving `latest` could never do
2. Argo runs inside the cluster (`ns argocd`, 0.5–1 GB RAM) and polls GitHub; a webhook is optional
3. One Argo **Application** per Helm release (`central-db`, `payment-edge-cell`, `payment-edge-workers`, `payment-central-relay`, `payment-consumers`, `keycloak`, `kafka`, `redis`, `ingress-nginx`, `cloudflared`, `opentelemetry-collector`, `monitoring`, `tempo`, `kafka-exporter`, `postgres-exporter`, `mor-backoffice`, `checkout-demo`): chart, `values.yaml` + `prod/values.yaml` + `secrets://…`, release name, namespace, sync wave
4. An **app of apps**: one root Application lists the others; a blank cluster is bootstrapped with one script. That is the "rebuild in under an hour" button
5. Rollback is "sync to the previous commit"
6. The Argo UI over Tailscale (`kubectl port-forward`) or at `argocd.dc4pay.com` behind Cloudflare Access

Sync waves mirror how-to-start's order: wave 1 Keycloak, Kafka, Redis, ingress-nginx, cloudflared; wave 2 central-db, edge cell, edge-workers, relay, consumers, OTel collector, mor-backoffice, checkout-demo; wave 3 monitoring, Tempo, exporters. `setup-keycloak-prod.sh` is the one step Argo does not do (it talks to Keycloak's admin API, not to Kubernetes).

### 3.6 Terraform: Cloudflare only

Terraform created the AKS cluster on Azure. On the M1 there is nothing for it to create: the cluster is OrbStack on a Mac (no provider), and everything inside the cluster is Argo's job. Terraform is kept **only for Cloudflare** — DNS records, the tunnel and its ingress rules, the Access policies (`/admin`, Grafana, Argo), the rate-limit rule — about ten resources with the official provider, run from the laptop, state out of git (it holds the tunnel token: Terraform Cloud free tier or a SOPS-encrypted state file). Optionally the Tailscale ACL. Phase 2: first make the tunnel work by hand with the `cloudflared` CLI and the dashboard, then import into Terraform. `infra/terraform` (Azure) stays untouched and unused.

### 3.7 Monitoring is part of the deployment

kube-prometheus-stack (Prometheus 2 h retention, Grafana, Alertmanager, node-exporter, kube-state-metrics) and Tempo (24 h), plus the Kafka and Postgres exporters, as `deploy-monitoring-stack-local.sh` installs them locally. The OTel collector already remote-writes metrics to that Prometheus and sends traces to Tempo (`infra/helm-values/opentelemetry-collector-values-local.yaml`); nothing is repointed to a hosted service. Grafana at `grafana.dc4pay.com` behind Cloudflare Access; Prometheus and Alertmanager over Tailscale. An **external uptime check** (Better Stack or UptimeRobot free tier) on `api.dc4pay.com/healthz` and the checkout URL — Alertmanager inside the box cannot say the box is down or the tunnel dropped.

## 4. What runs on the server

### 4.1 RAM budget (from the memory limits in the local values; the ceiling, not typical usage)

| Component | Limit | Source |
| --- | --- | --- |
| payment-service + edge-db (one cell, same pod) | 768 Mi + 500 Mi | `charts/payment-edge-cell/local/values.yaml` |
| payment-edge-workers | 500 Mi | `charts/payment-edge-workers/local/values.yaml` |
| payment-central-relay | 700 Mi | `charts/payment-central-relay/local/values.yaml` |
| payment-consumers | 700 Mi | `charts/payment-consumers/local/values.yaml` |
| central-db | 800 Mi (`shared_buffers=256MB`, `max_connections=55`) | `charts/central-db/local/values.yaml` |
| Keycloak + its Postgres | 1 Gi + ~256 Mi (Bitnami default) | `infra/helm-values/keycloak-values-local.yaml` |
| Kafka (KRaft, one controller+broker pod, 1 GB heap) | 1000 Mi | `infra/helm-values/kafka-values-local.yaml` |
| Redis (standalone, `maxmemory 380mb`, no persistence) | 128 Mi | `infra/helm-values/redis-values-local.yaml` |
| ingress-nginx | 400 Mi | `infra/helm-values/ingress-nginx-values-local.yaml` |
| OTel collector | 512 Mi–1 Gi | `infra/helm-values/opentelemetry-collector-values-local.yaml` |
| Prometheus | 600 Mi | `infra/helm-values/monitoring-stack-values-local.yaml` |
| Grafana | 1 Gi | same |
| Tempo | 1 Gi | `infra/helm-values/tempo-values-local.yaml` |
| Alertmanager, operator, node-exporter, kube-state-metrics, Kafka/Postgres exporters | ~350 Mi | same files |
| cloudflared + Argo CD | 0.6–1.1 GB | new |
| mor-backoffice + checkout-demo | ~0.3 GB | new |
| Kubernetes system pods inside the OrbStack VM | ~0.5 GB | — |
| OrbStack VM overhead + macOS itself | 2 GB | fixed cost before a single pod runs |

Sum of the limits inside the VM: about 12 GB. Pods rarely sit at their limit; the VM is 14 GB. If it gets tight, Grafana and Tempo at 512 Mi each are enough for one person. Do not shrink Kafka, Keycloak or the databases. Each JVM app already gets its heap from the chart (`jvmOpts` with `-XX:MaxRAMPercentage=45` against the container limit): the memory limits in `prod/values.yaml` are the knob, no `-Xmx` needed.

### 4.2 Facts that shape the plan

- Deploy the charts unchanged — one edge cell, no code changes to the services beyond `application-prod.yml`
- **No KEDA.** The consumers also serve the balance, transactions and accounts API the back office calls, so they can never go to zero; the chart's `ScaledObject` has `minReplicaCount: 1` and its trigger names a topic and group that no longer exist (`gateway.capture.commands` / `psp-capture-executor-consumer-group`; real: `gateway.capture.requested` / `capture.psp.command.executor`). Fix the values before ever enabling it for load tests. The relay is a singleton (`replicaCount: 1`) by design
- Storage: `edge-db` is a 20 Gi PVC in the edge-cell StatefulSet; `central-db` a PVC in its chart; Kafka keeps 7 days / 10 GiB per partition (`log.retention.hours`, `log.retention.bytes`) — the biggest disk consumer. Prometheus 2 h and Tempo 24 h are already bounded. The SSD is the only copy: a daily `pg_dump` of both Postgres volumes pushed off-box is the recovery point. Keycloak's Postgres needs no backup: the realm is rebuilt from `keycloak/realm/*.json`
- **Two apps are not in the cluster yet.** `checkout-demo` (page on 3000, proxy on 3001) and `mor-backoffice` (page on 3100, server on 3101) run today with `npm run dev` on the developer's Mac and find Keycloak and the API via `kubectl`. Neither has a Dockerfile or a chart. Each gets one (runbook 3.7): `PAYMENT_API_BASE_URL=http://ingress-nginx-controller.ingress-controller.svc.cluster.local` instead of the `kubectl` lookup; for the back office `PUBLIC_URL=https://backoffice.dc4pay.com` (makes its cookie `secure`) and a fixed `SESSION_SECRET` from SOPS (a random one per start logs everyone out on every restart); sessions are in memory, so one replica

### 4.3 Hardware and OS requirements (procedure in the runbook, Phases 0–2)

| Topic | Requirement |
| --- | --- |
| Power | Power Nap off, Wake for network access on, `pmset autorestart 1` so it powers back on after an outage |
| Lid | `pmset -c disablesleep 1`: closing the lid never sleeps the machine |
| Display | Panel dead, lid closed → clamshell mode; an HDMI dummy plug makes Screen Sharing behave; not needed for SSH |
| Login | A standard (non-admin) service user with automatic login runs OrbStack as a Login Item; an admin user for SSH |
| FileVault | Off: an encrypted disk asks for a password at boot that nobody is there to type |
| Updates | Manual only: an unannounced update and reboot is the biggest self-inflicted risk |
| Network | Hostname `m1-server`; DHCP reservation on the router; Remote Login (SSH) and Screen Sharing on, admins only; macOS firewall on |

## 5. Security and reliability

- SSH only over Tailscale; the Kubernetes API never exposed; only the services in the hostname table go through the tunnel
- Cluster services run under the non-admin macOS user
- Keycloak's admin console, Grafana and Argo behind Cloudflare Access; the API is not (API clients cannot pass Access)
- Cloudflare rate-limit rule on `POST api.dc4pay.com/api/v1/payments` (public demo credentials)
- Rotate the Cloudflare tunnel token on a schedule; no registry pull token exists (Docker Hub public repositories)
- Rebuild target: OS, cluster, data restore from git and backups in under an hour — proven by the rebuild drill (runbook Phase 7), not assumed
- A full disk takes down every service at once: retention stays bounded (§4.2), disk usage is on the Grafana dashboard

## 6. Open questions and things to verify on the machine

- **Stripe webhook** (not for this project phase): payment-service has `POST /webhook` (`WebhookController`, needs `stripe.webhook-secret`), but the edge Ingress exposes only `/api/v1/payments` and `/healthz`, so it is not reachable today. A Stripe webhook carries Stripe's intent id, not our `pi_`, so the cell router cannot send it to the right cell; with one cell it would work, with N it would not. `architecture.md` already places `capture_confirmed` with the consumers, which is where a webhook receiver belongs
- OrbStack's default VM memory and the port its Kubernetes API listens on
- Whether the Bitnami chart runs Keycloak in production or dev mode
- Whether Keycloak's admin API accepts requests over a port-forward once `KC_HOSTNAME_URL` is public (runbook 4.4 has the fallback)
- The exact `helm-secrets` sidecar manifest for the Argo repo-server and the `secrets://` path form it expects (runbook 3.8 points at the plugin's current docs)
