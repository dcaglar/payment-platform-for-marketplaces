# M1 MacBook Pro → Always-On Bare-Metal Kubernetes Server

Oct 14, 2026 · @Dogan Caglar

## Goals and scope

Turn the retired 14-inch MacBook Pro (M1 Pro, 8-core CPU, 16 GB RAM, 512 GB SSD, built-in display dead) into an always-on, internet-reachable Kubernetes node running the payment platform's existing Helm topology, unchanged from what already runs locally in OrbStack.

Success criteria:

- [ ] Boots headless (lid closed, no monitor) and rejoins the LAN on its own after a power cut
- [ ] The full topology (Kafka, Postgres, Redis, Keycloak, four JVM services) starts from a cold boot with no manual steps
- [ ] The checkout URL and its API are reachable from the public internet over TLS, with no port forwarded on the router
- [ ] SSH and the Kubernetes API stay private, reachable only over Tailscale, never directly from the internet
- [ ] A full rebuild from git plus backups finishes in under an hour

## Before anything else: back up and sign out

This machine was Dogan's personal laptop before it becomes a server. Nothing about the server setup below should start until this is done.

- [ ] Full Time Machine backup (or at least Photos, Documents, Desktop, any local project folders not already in git)
- [ ] Confirm anything important is also in iCloud/Drive/git, not only on this disk
- [ ] Sign out of iMessage, FaceTime and the App Store (System Settings > each app, or Apple ID > Sign Out) — a headless Mac still signed in can keep receiving messages and notifications tied to it
- [ ] Sign out of iCloud, or at minimum turn off iCloud Drive/Photos sync, since the server has no screen to approve prompts on
- [ ] Decided: clean erase-and-reinstall of macOS once the backup above is confirmed — no personal apps or accounts carried forward onto the server

## Hardware and OS preparation

| Step | Action |
| --- | --- |
| Power | System Settings > Battery > Options: turn off Power Nap, turn on Wake for network access; `sudo pmset -a autorestart 1` so it powers back on after an outage |
| Lid | `sudo pmset -c disablesleep 1` so closing the lid (already necessary, the screen is dead) never sleeps the machine |
| Login | Create a dedicated non-admin user for running services; enable automatic login for that user only, since no screen will ever be present to type a password |
| FileVault | Turn off — an encrypted disk demands the password at every boot, which a headless machine can never supply |
| Updates | Set macOS update checks to manual only; an unannounced update and reboot mid-demo is the single biggest self-inflicted risk here |
| Network | DHCP reservation on the router so the Mac keeps a fixed LAN IP; enable Remote Login (SSH) and Screen Sharing under Sharing |

## Kubernetes runtime choice

|  | OrbStack (macOS) | Asahi Linux + k3s |
| --- | --- | --- |
| Setup effort | None — already configured, charts deploy as-is | A clean OS install, everything re-provisioned from git |
| RAM overhead | A Linux VM layer sits under every pod | None — k3s runs natively on bare metal |
| Familiarity | Same environment already used for local development | New: systemd, native Linux networking |
| Risk | Lowest, already proven | Higher short-term, removes the VM tax permanently |

Stay on OrbStack for the first deployment to get the server live quickly. Revisit Asahi Linux with k3s once the platform is stable, to reclaim the RAM the VM layer costs on a 16 GB machine.

Important default to override: OrbStack caps its VM at 8 GB of memory regardless of host RAM — a sensible default on a machine shared with other apps, wrong on a dedicated server. Raise it in Settings > Resources (or `orb config set memory_mib 13312`) to around 13 GB, leaving the remaining 2-3 GB for macOS itself per the RAM budget below. CPU needs no equivalent change — OrbStack only uses as much CPU as the workloads actually demand.

## Redeploying the Helm charts

The real bring-up, from the project's own scripts (`infra/scripts/`): `deploy-all-external-infra-local.sh` (Keycloak, Redis, Kafka) first, then `deploy-payment-platform-services-local.sh` (the edge cell, `payment-edge-workers`, `payment-central-relay`, `payment-consumers`). Images are already built and pushed to a registry by `build-and-push-local.sh`, so the home server pulls the same way the Mac does today.

Rough RAM budget on a 16 GB box, nothing else running:

| Component | Approx. RAM | Note |
| --- | --- | --- |
| payment-service + edge-db | 0.7–1 GB | one edge cell only |
| payment-edge-workers | 0.3–0.5 GB |  |
| payment-central-relay + payment-consumers + central-db | 1–1.5 GB |  |
| Keycloak | \~1 GB | heaviest single app besides Kafka |
| Kafka (KRaft, one broker) | 1–1.5 GB |  |
| Redis | \~0.2 GB |  |
| OrbStack's Linux VM + macOS itself | 2–3 GB | fixed cost before a single pod runs |

That's 6–9 GB for the platform itself, out of 16 GB — real headroom, as long as one thing is skipped.

**Skip the monitoring stack and its exporters** (`deploy-monitoring-stack-local.sh` and the Kafka/Postgres exporters) — the how-to-start guide already marks both optional. That's `kube-prometheus-stack` (Prometheus, Grafana, Alertmanager, node-exporter, kube-state-metrics), easily another 1.5–2.5 GB, for dashboards this server doesn't need a person watching. Keep the app's own OTel Collector, but repoint its exporters at a hosted free tier (Grafana Cloud or similar takes OTLP traces and metrics directly) instead of a local Prometheus and Tempo — same visibility, none of the RAM.

Otherwise:

- Deploy the charts unchanged — one edge cell, no code changes
- Give each of the four JVM apps an explicit `-Xmx`
- Turn on KEDA so idle consumers scale to zero between test runs
- Back up both Postgres volumes (`edge-db` and `central-db`) with a daily `pg_dump` pushed off-box — the SSD is otherwise the only copy

## Exposing the cluster to the internet

&#91;embedded content: traffic path · 5 steps, outbound tunnel\]

A request never opens a connection into the Mac. cloudflared, running on the Mac, dials out to Cloudflare and holds that connection open; a public request rides back over it to ingress-nginx on `localhost:80`, which routes it by Host header to the right service.

Setup:

1. Domain registered: dc4pay.com, done directly through Cloudflare — already on Cloudflare's nameservers, nothing more needed here
2. `brew install cloudflared`, then `cloudflared tunnel login` and `cloudflared tunnel create payment`
3. In `~/.cloudflared/config.yml`, route checkout.dc4pay.com to `http://localhost:80`
4. `cloudflared tunnel route dns payment checkout.dc4pay.com` to create the DNS record
5. `sudo cloudflared service install` so it starts with the Mac, no manual step after a reboot

Point Stripe's webhook URL at checkout.dc4pay.com once it's live.

## Security hardening

- SSH only over Tailscale's private network — the public internet never reaches it directly
- The Kubernetes API is never exposed; only ingress-nginx goes through the tunnel
- Run cluster services under a dedicated, non-admin macOS user, not full admin
- Put Keycloak's admin console and Grafana behind Cloudflare Access instead of leaving them open on the public hostname
- Keep the macOS firewall on; allow only SSH and what OrbStack needs locally
- Rotate the Cloudflare Tunnel token and the GHCR pull token on a schedule, not only at first setup

## Reliability and recovery

- Target: rebuild the whole environment — OS, cluster, data restore — from git and backups in under an hour
- The SSD is the only copy of the data; the daily off-box Postgres backup is the real recovery point, not a convenience
- Set generous retention limits on logs, traces and Kafka segments — a full disk takes down every service at once, not just one
- `sudo pmset -a autorestart 1` so the Mac powers back on by itself after an outage, with no one there to press the button
- Keep a short written runbook: how to re-provision from a blank disk, where backups live, what order services need to start in

## Monitoring and CI/CD

|  | Self-hosted runner | GitOps pull (Argo CD / Flux) |
| --- | --- | --- |
| Needs inbound access to the cluster | Yes — the runner holds full cluster credentials | No — Argo runs inside the cluster and pulls |
| CI's job | Build, test, `helm upgrade` directly | Build, test, push the image, bump a tag in a config repo |
| Audit trail | Only in CI logs | Full history in git, with drift detection |

Go with GitOps pull. CI keeps building, testing and pushing images to its existing registry (Docker Hub, via \`build-and-push-local.sh\`), using a per-commit tag instead of \`latest\` so Argo has something to diff against, then bumps the image tag in a config repo; Argo CD, running inside the cluster, watches that repo and syncs. No cluster credentials ever leave the Mac, and nothing needs an inbound connection.

Add an external uptime check (Better Stack or UptimeRobot's free tier) against the public checkout URL — nothing here pages out on its own if the tunnel silently drops.

Manage the Cloudflare side — DNS records, tunnel ingress rules, Access policies — with Terraform, alongside the Helm charts, so the edge stays infra-as-code too.
