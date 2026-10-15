# M1 server — Runbook

Oct 15, 2026 · @Dogan Caglar. The **how**; the what and why is [`m1alwayson-baremetal.md`](./m1alwayson-baremetal.md). Phases run in order; each has *Goal → Prerequisites → Steps → Done when → If it fails*. Lines marked **VERIFY** can only be settled on the machine or against a tool's current docs: correct them here while executing. This file is meant to be edited as you go.

Names used throughout: **server** = the old M1 (`m1-server`), **laptop** = your current Mac. `$REPO` = the checkout of this repository on whichever machine the step runs on.

---

## Phase 0 — Back up and erase (server, with a monitor)

**Goal:** the old laptop holds nothing personal and runs a fresh macOS.
**Prerequisites:** an external monitor with HDMI, a **USB keyboard with a USB‑C adapter** (a mouse is optional), the Mac's **own charger**; a Time Machine disk or enough iCloud/Drive space.

1. Full Time Machine backup; confirm Photos, Documents, Desktop and any project folder not in git are on it or in iCloud/Drive/git
2. Sign out: Messages → Settings → iMessage → Sign Out; FaceTime → Settings → Sign Out; App Store → account → Sign Out; System Settings → Apple ID → Sign Out (choose to keep nothing). **Turn Find My off here** (Activation Lock), or expect the Activation Lock screen in Phase 1
3. System Settings → General → Transfer or Reset → **Erase All Content and Settings**; the Mac reboots into Recovery Assistant / Setup Assistant

**Done when:** Setup Assistant's language screen is up, and the Time Machine backup is verified readable from the laptop (open it, find one file).

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| "Erase All Content" greyed out | Activation Lock / Find My still on | Apple ID → Find My → off first |
| **Activation Lock** screen: "This Mac is linked to an Apple ID" | Find My was still on | sign in with your own Apple ID; if the spinner runs >3 min, restart and enter it again (happened 2026‑10‑15, second attempt went through); "Use Device Password" (old login password) also works; last resort: icloud.com/find → device → Remove |
| Mac boots into **Recovery Assistant** (menu bar only, grey screen) instead of Setup Assistant | disk erased from Recovery / Disk Utility, so there is no macOS to boot | Wi‑Fi icon top-right → join; Recovery window → **Reinstall macOS** → target `Macintosh HD` → 30–60 min, several reboots, keep monitor and charger connected; then Setup Assistant appears |
| **Only the menu bar on the external monitor, no window** | the dead built-in panel is still detected and is the main display; every window opens there | **clamshell mode**: Mac's own charger plugged in (a monitor's USB‑C port is not enough), USB keyboard plugged in, **lid closed**. Turn on by unplugging/replugging the charger or open lid → press power once → close lid. The external is then the only display |
| Reinstall says no volume to install on | the volume was deleted, not erased | Disk Utility → View → Show All Devices → top-level Apple SSD → Erase, APFS, name `Macintosh HD` → quit → Reinstall macOS |

---

## Phase 1 — macOS base (monitor + USB keyboard until SSH works, then headless)

**Goal:** headless-safe macOS, reachable by SSH on the LAN, that survives a power cut without anyone present.
The built-in panel is dead, so the monitor is needed only until SSH is on (steps 1–3). Everything after step 4 is done from the laptop over SSH; no GUI step is required.

1. Setup Assistant (monitor, lid closed, USB keyboard): **Set up as new**; region; join the home Wi‑Fi; **Don't sign in with Apple ID** (Set Up Later → Skip); create admin account `dogan` (Full Name and Account Name both `dogan`), **uncheck** "Allow computer account password to be reset with your Apple Account"; Location Services off; Siri off; Screen Time skip; **FileVault: do not enable**; Touch ID skip
   - Keyboard only: **Tab** moves between controls, **Space** presses the focused one, **Enter** presses the blue default button, arrows move in lists. If Enter does nothing, a VoiceOver window is behind the assistant: **Command+F5** turns VoiceOver off. Mouse Keys (Command+Option+F5 → Motor) was not needed
2. At the desktop (monitor): **Command+Space**, type `Terminal`, Enter. Turn SSH on from the Terminal (`systemsetup -setremotelogin on` fails from Terminal with "requires Full Disk Access"):
   ```bash
   sudo launchctl enable system/com.openssh.sshd
   sudo launchctl bootstrap system /System/Library/LaunchDaemons/ssh.plist
   sudo launchctl print system/com.openssh.sshd | head -5   # listed = on; "not running" is normal (socket-activated)
   ipconfig getifaddr en0                                   # note the IP
   ```
3. From the laptop: `ssh dogan@<ip>` → works → **unplug the monitor and keyboard; lid stays closed, charger stays in.** Everything below is typed in that SSH session.
4. Names:
   ```bash
   sudo scutil --set ComputerName m1-server
   sudo scutil --set HostName m1-server
   sudo scutil --set LocalHostName m1-server
   ```
5. Service user (no GUI needed). `sysadminctl -autologin` fails from SSH with `SACSetAutoLoginPassword error:22`, so auto-login is written by a small MIT-licensed script (XOR-encodes the password into `/etc/kcpassword` and sets `autoLoginUser`; read it first, it is 90 lines of plain bash):
   ```bash
   read -s SVC_PW; echo                                   # type the svc password (nothing is echoed), store it in the password manager
   sudo sysadminctl -addUser svc -fullName svc -password "$SVC_PW"   # prints Error:-14120 (secure token) — harmless, FileVault is off
   dscl . -authonly svc "$SVC_PW" && echo PASSWORD_OK
   curl -fsSL https://raw.githubusercontent.com/brunerd/macAdminTools/main/Scripts/setAutoLogin.sh -o /tmp/setAutoLogin.sh
   sudo bash /tmp/setAutoLogin.sh svc "$SVC_PW"           # "Auto login enabled for 'svc'"
   unset SVC_PW
   ```
   Skip the first-login assistant for `svc` by copying the completed flags from `dogan` (otherwise `svc` sits in "Accessibility / Apple Account" screens after every boot and login items never start):
   ```bash
   sudo cp /Users/dogan/Library/Preferences/com.apple.SetupAssistant.plist /Users/svc/Library/Preferences/com.apple.SetupAssistant.plist
   sudo chown svc:staff /Users/svc/Library/Preferences/com.apple.SetupAssistant.plist
   ```
   Changing the svc password later: `sudo passwd svc`, then run the script again with the new password, then `sudo rm -f /Users/svc/Library/Keychains/login.keychain-db` (keychain was encrypted with the old one; it is empty).
6. Power:
   ```bash
   sudo pmset -a autorestart 1      # ignored on MacBooks (no such setting); Apple-silicon laptops power on when power returns anyway
   sudo pmset -c disablesleep 1     # never sleep on power, lid closed or not
   sudo pmset -a powernap 0
   sudo pmset -a womp 1             # wake for network access
   sudo pmset -a displaysleep 1     # the dead panel can "sleep" immediately
   pmset -g | grep -iE 'sleepdisabled|womp|powernap'   # SleepDisabled 1, womp 1, powernap 0
   ```
7. Updates off. `softwareupdate --schedule off` is a no-op on current macOS (still prints "turned on"); write the keys directly:
   ```bash
   for k in AutomaticDownload AutomaticallyInstallMacOSUpdates ConfigDataInstall CriticalUpdateInstall; do
     sudo defaults write /Library/Preferences/com.apple.SoftwareUpdate $k -bool false; done
   sudo defaults write /Library/Preferences/com.apple.commerce AutoUpdate -bool false
   defaults read /Library/Preferences/com.apple.SoftwareUpdate | grep -E 'Automatic|Install'   # all 0
   ```
   `AutomaticallyInstallMacOSUpdates = 0` is the one that prevents unattended reboots; checking stays on and is harmless.
8. Firewall on, SSH allowed: `sudo /usr/libexec/ApplicationFirewall/socketfilterfw --setglobalstate on` (stealth/block-all stay off; sshd is a signed system service and is allowed)
9. Screen Sharing for the rare GUI task (same launchd pattern as SSH):
   ```bash
   sudo launchctl enable system/com.apple.screensharing
   sudo launchctl bootstrap system /System/Library/LaunchDaemons/com.apple.screensharing.plist
   ```
   Laptop: `open vnc://m1-server.local`, log in as `dogan`. The `svc` desktop locks after a few idle minutes; that is the lock screen, the session keeps running.
10. No router DHCP reservation: macOS uses a private (randomised) Wi‑Fi address by default, and the server is always reached by name (`m1-server.local` on the LAN, Tailscale from anywhere).
11. Reboot with the lid closed (`sudo reboot`; the SSH session hangs then drops, normal). From the laptop: `ssh dogan@m1-server.local who`

**Done when:** after a closed-lid reboot, `ssh dogan@m1-server.local` works within two minutes without touching the machine; `pmset -g | grep -E 'autorestart|disablesleep|womp'` shows `1`; `fdesetup status` → `FileVault is Off`; `who` shows `svc` on `console` (auto-login happened).

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `m1-server.local` does not resolve from the laptop | mDNS blocked by the router/Wi‑Fi client isolation, or laptop and server on different bands with isolation | use the reserved IP; check router "AP isolation"; both on the same SSID |
| SSH refused after reboot but ping works | sshd not enabled persistently | over Screen Sharing or monitor: step 2 again; `launchctl enable` is what persists |
| `launchctl load -w ssh.plist` → "Load failed: 5: Input/output error" | legacy subcommand on current macOS | use `launchctl enable` + `bootstrap` (step 2) |
| Machine asleep after lid close | `disablesleep` applied for battery (`-b`) only, or not on the charger | `sudo pmset -c disablesleep 1`; the Mac's own charger, not a monitor's USB‑C |
| Auto-login did not happen | FileVault on, or svc password changed without rewriting `/etc/kcpassword` | `fdesetup status`; run `setAutoLogin.sh svc <pw>` again (step 5) |
| LG shows "Accessibility" / "Sign in to your Apple Account" for svc after boot | first-login assistant for a new user; `Not Now` is not reachable with Tab | step 5 plist copy, then `sudo pkill -u svc -f "Setup Assistant"` and `sudo reboot` |
| `ssh host` pasted together with commands → repeated `Password:` prompts | the pasted commands were consumed as password attempts | paste the `ssh` line alone, wait for the `dogan@m1-server` prompt, then the commands |
| `sysadminctl -autologin set` → `SACSetAutoLoginPassword error:22` | not supported from an SSH session | step 5 script |
| `/tmp/setAutoLogin.sh: No such file` | `/tmp` is wiped at reboot | `curl` it again |
| Screen Sharing connects but shows a tiny or black desktop | no display enumerated (dead panel not detected, no monitor) | still usable; if unbearable, a €5 HDMI dummy plug gives macOS a fake display |
| `ssh` asks for a password every time | key not copied yet | that is Phase 2 step 1 (`ssh-copy-id`) |

From here everything is done from the laptop.

---

## Phase 2 — Connect, toolchain, Tailscale, OrbStack, server key (laptop → server)

**Goal:** the server is reachable from anywhere over Tailscale, has the toolchain, a 14 GB Kubernetes, and its own age key; the laptop can edit secrets both machines can read.

1. Keys and SSH hardening (done 2026‑10‑15):
   ```bash
   ssh-copy-id dogan@m1-server.local      # laptop: appends ~/.ssh/id_ed25519.pub to the server's ~dogan/.ssh/authorized_keys (asks the password one last time)
   ssh dogan@m1-server.local              # laptop: must now log in with no password prompt
   ```
   On the server, in that session:
   ```bash
   sudo sed -i '' -e 's/^#\{0,1\}PasswordAuthentication .*/PasswordAuthentication no/' \
                  -e 's/^#\{0,1\}KbdInteractiveAuthentication .*/KbdInteractiveAuthentication no/' /etc/ssh/sshd_config
   sudo launchctl kickstart -k system/com.openssh.sshd
   ```
   - `sed -i ''` edits `/etc/ssh/sshd_config` in place (macOS form, no backup file). The two patterns replace the lines `PasswordAuthentication` and `KbdInteractiveAuthentication`, whether commented out (`#`) or not, with `… no`. Result: sshd accepts only key-based logins; a password, even the right one, is refused with `Permission denied (publickey)`. Brute-force attempts become pointless
   - `launchctl kickstart -k` restarts the sshd service so the new config applies; existing sessions survive
   - Verify from the laptop, before closing the session that made the change: `ssh dogan@m1-server.local hostname` → `m1-server`; `ssh -o PubkeyAuthentication=no dogan@m1-server.local` → `Permission denied (publickey)`
   - Lost the key? The only ways back in are Screen Sharing (`vnc://`, still password-based) or the monitor. Keep the laptop's `~/.ssh/id_ed25519` backed up with the rest of the laptop
2. Toolchain (on the server):
   ```bash
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   brew install tailscale kubectl helm sops age git jq yq
   brew install --cask orbstack
   helm plugin install https://github.com/jkroepke/helm-secrets --verify=false   # Helm 4 verifies plugin signatures; this plugin has none yet
   git clone https://github.com/dcaglar/payment-platform-for-marketplaces.git ~/payment-platform
   ```
3. Tailscale as a system daemon (so it is up before anyone logs in):
   ```bash
   sudo brew services start tailscale
   sudo tailscale up --hostname m1-server
   ```
   It prints a login URL: open it on the laptop, sign in. Then in the Tailscale admin console (login.tailscale.com): Machines → `m1-server` → "…" → **Disable key expiry**. MagicDNS is on by default (DNS tab shows "Disable MagicDNS"); tailnet name is `tail5273d7.ts.net`. `tag:server` is optional (needs an access-policy edit), skipped for now
4. Tailscale on the laptop: the Mac app (App Store, or `brew install --cask tailscale-app`), same account. **NordVPN off** while connecting. Test: `ssh dogan@m1-server` (no `.local`), then from your phone on mobile data with the Tailscale app: `ssh` works there too → the server is reachable from anywhere
5. Screen Sharing (done 2026‑10‑15). The switch **must be flipped once in System Settings → General → Sharing**; `launchctl`/`kickstart` leave the service "not permitted" (Apple: "must be enabled from System Settings or via MDM"). Access stays **administrators only**. From the laptop: `open vnc://m1-server`, log in as `dogan`; because `svc` holds the console, pick **Log in as yourself**, then the user menu (top right) → `svc` → its password. No approval on the server is needed that way. "Ask for permission" needs a keypress on the M1, avoid it.
6. OrbStack, once, in that Screen Sharing window on the `svc` desktop (it is a desktop app; this is the only GUI step left): Launchpad → OrbStack → admin helper prompt: `dogan`. Settings → System: memory limit default was **8 GiB**, slider cap **12 GiB** (= ¾ of 16 GB) → set **12 GiB**; **Pause containers while Mac is sleeping: off**; **Use admin privileges: on**; Apply and Restart. Settings → Kubernetes → Enable; leave "Expose services to LAN" **off** (LoadBalancer/API stay on localhost; cloudflared and Tailscale are the only doors). Everything after this is terminal.
7. `svc`'s shell over SSH (no GUI): `ssh dogan@m1-server` → `sudo -iu svc` (sudo asks for **dogan**'s password). Then:
   ```bash
   echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile && source ~/.zprofile
   orb config set app.start_at_login true   # the Login Item, settable from the CLI
   orb config show | grep -E 'k8s.enable|memory_mib|start_at_login'
   orb restart k8s                          # first `orb start k8s` returned silently without a kubeconfig; restart brought it up
   kubectl get nodes                        # orbstack  Ready  control-plane  v1.36.5+orb1
   kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'; echo   # https://127.0.0.1:26443
   kubectl config rename-context orbstack m1-prod
   kubectl config use-context m1-prod
   kubectl config get-contexts              # only m1-prod
   ```
   **VERIFY after the next reboot:** OrbStack may rewrite `~/.kube/config` and bring the `orbstack` context name back; if so, the `bootstrap-prod.sh` guard must accept the renamed context or the rename must be redone.
8. Server age key, as `svc` (Argo's bootstrap reads it later):
   ```bash
   mkdir -p ~/.config/sops/age && age-keygen -o ~/.config/sops/age/keys.txt
   grep 'public key' ~/.config/sops/age/keys.txt    # age1g636…madks (m1-server, 2026‑10‑15)
   ```
   On the laptop, `.sops.yaml` lists both recipients (laptop first, server second); re-encrypt and push:
   ```bash
   for f in *-sops-secrets.yaml; do sops updatekeys -y "$f"; done
   git commit -am "sops: add m1-server age recipient" && git push
   ```
   On the server, as `svc`: `git clone https://github.com/dcaglar/payment-platform-for-marketplaces.git ~/payment-platform && cd ~/payment-platform && sops -d central-db-sops-secrets.yaml | head -2` prints plaintext keys.

**Done when:** `ssh dogan@m1-server` works from outside the home network; `kubectl --context m1-prod get nodes` as `svc` shows `Ready`; `kubectl config get-contexts` has **no** `orbstack` entry; `sops -d` works on the server as `svc`; the laptop's key file never left the laptop.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `tailscale up` says "Logged out" after a while | key expiry not disabled | admin console → Disable key expiry |
| `ssh dogan@m1-server` hangs from the laptop, `tailscale status` shows the server but no traffic | NordVPN (or any other VPN) active on the laptop; it captures the 100.x routes | disconnect NordVPN while working on the server; never install it on the server |
| `m1-server.local` fails but `m1-server` (Tailscale) works | TP‑Link Wi‑Fi "AP isolation" / guest network blocks LAN mDNS | use the Tailscale name; or turn AP isolation off in the Tether app |
| `brew upgrade tailscale` fails with permission errors | the daemon runs as root and `brew services` made the binary root-owned | `sudo brew services stop tailscale`, `brew upgrade tailscale`, `sudo brew services start tailscale` |
| `brew services start tailscale` runs as user, not at boot | forgot `sudo` | `sudo brew services start tailscale` (system LaunchDaemon) |
| OrbStack not running after a reboot | `app.start_at_login` false, or auto-login user is not `svc` | `orb config set app.start_at_login true` as `svc`; `sudo defaults read /Library/Preferences/com.apple.loginwindow autoLoginUser` |
| `orb start k8s` returns at once, `~/.kube` stays empty | first start did not come up | `orb restart k8s`, then `kubectl get nodes` |
| Screen Sharing: "not permitted on m1-server" | enabled from the CLI, not System Settings | flip the switch off/on in System Settings → Sharing (once, needs the monitor) |
| Screen Sharing refuses `svc`'s password | access list is administrators only (keep it) | connect as `dogan` → Log in as yourself → switch user to `svc` |
| `kubectl` as `dogan` sees no cluster (`localhost:8080 refused`) | kubeconfig belongs to `svc` | `sudo -iu svc` first |
| `sops updatekeys`: "no matching creation rules" / key not found | `.sops.yaml` syntax; laptop key path | `SOPS_AGE_KEY_FILE=~/Library/Application\ Support/sops/age/keys.txt` (macOS default) |

---

## Phase 3 — Repo work (laptop; nothing touches the server)

**Goal:** everything `prod` needs exists in git and passes the build; `local` is untouched. Each sub-step is one PR. Verification in this phase is build-level (`mvn clean install -DskipITs`, `helm template`, `grep`); the integration test of the Keycloak change happens in Phase 4 on the blank server cluster, which respects the no-shared-scripts rule.

### 3.1 Build scripts: track-neutral names

1. `git mv infra/scripts/build-and-push-local.sh infra/scripts/build-and-push.sh` — **compare first** with the existing CI `infra/scripts/build-and-push.sh` (`diff`), keep one script that takes `<module> <dockerhub-user> <tag...>` and pushes **every tag given** (the CI step needs `<sha>` and `latest` from one build)
2. `git mv infra/scripts/build-all-payment-platform-images-and-push.sh infra/scripts/build-all-images.sh`; it calls `build-and-push.sh`; add `mor-backoffice` and `checkout-demo` once 3.7 exists
3. Update references: `docs/how-to-start.md`, `payment-service/CLAUDE.md`, `deploy-all-local.sh`, `.github/workflows/*.yml`
4. Done when: `grep -rn 'build-and-push-local\|build-all-payment-platform-images' --exclude-dir=target .` returns nothing; `infra/scripts/build-all-images.sh dcaglar1987 latest` still pushes the four images

### 3.2 `application-prod.yml`

For every module that has `src/main/resources/application-local.yml` (`payment-service`, `payment-consumers`, check `payment-edge-workers` and `payment-central-relay`): copy it to `application-prod.yml` in the same folder, then:

- `payment-service` and `payment-consumers`: under `spring.security.oauth2.resourceserver.jwt` add `jwk-set-uri: ${KEYCLOAK_JWK_SET_URL}` (keep `issuer-uri`). With both set, Spring Boot uses the JWKS URL for keys and the issuer only to validate `iss`
- Keep `psp.gateway.type` as in local (the chart's `PSP_GATEWAY_TYPE=STRIPE` env overrides it anyway)
- Nothing else changes in the first version; prod-specific tuning comes later, one line at a time

Done when: `mvn clean install -DskipITs` green; `SPRING_PROFILES_ACTIVE=prod` is not referenced anywhere with a typo (`grep -rn 'application-prod' */src/main/resources`).

### 3.3 `charts/<chart>/prod/values.yaml` (our five charts)

Create from a copy of `local/values.yaml`, then edit:

| Chart | Changes vs local |
| --- | --- |
| all | `spring.profile: prod`; `image.tag` → the git sha of the commit CI will build first (write `REPLACED_BY_CI` until 3.10 runs once, then paste the sha). **VERIFY** the key path per chart: `helm show values charts/<chart>` — e.g. `payment-edge-cell` nests the image under its service block |
| `payment-edge-cell` | `config.KEYCLOAK_ISSUER_URL: https://auth.dc4pay.com/realms/ecommerce-platform`; add `config.KEYCLOAK_JWK_SET_URL: http://keycloak.payment.svc.cluster.local:8080/realms/ecommerce-platform/protocol/openid-connect/certs`; keep `pspGatewayType: STRIPE`; keep the `edgeDb` block (its absence is what broke the Azure render); ingress as local (host-less) |
| `payment-consumers` | same two Keycloak keys in `config`; `keda.enabled: false` stays |
| `payment-edge-workers` | remove `config.KEYCLOAK_ISSUER_URL` (dead) |
| `payment-central-relay` | `replicaCount: 1` stays |
| `central-db` | unchanged copy |

Done when: for each chart `helm template x charts/<chart> -f charts/<chart>/values.yaml -f charts/<chart>/prod/values.yaml` renders; `grep -l local charts/*/prod/values.yaml` prints nothing; `grep -rn KEYCLOAK charts/payment-edge-workers/prod` prints nothing.

### 3.4 `infra/helm-values/<component>-values-prod.yaml` (third-party charts)

Copy each `*-values-local.yaml` to `*-values-prod.yaml`, then:

| File | Changes vs local |
| --- | --- |
| `keycloak-values-prod.yaml` | `service.type: ClusterIP`; `ingress.enabled: true`, `ingress.hostname: auth.dc4pay.com`, `ingress.ingressClassName: nginx`; in `extraEnvVars` replace `KC_HOSTNAME` + `KC_HOSTNAME_PORT` with `KC_HOSTNAME_URL=https://auth.dc4pay.com`, `KC_HOSTNAME_ADMIN_URL=https://auth.dc4pay.com`, add `KC_HOSTNAME_STRICT_BACKCHANNEL=false`; keep `KC_PROXY=edge`, `KC_HOSTNAME_STRICT=false`; **remove** `auth.adminPassword` and `postgresql.auth.password` (they come from `keycloak-sops-secrets.yaml`); the image overrides the local script passes with `--set` (`bitnamilegacy/keycloak:23.0.7`, `bitnamilegacy/postgresql:16.4.0-debian-12-r0`) go into this file as values, since Argo has no `--set`. **VERIFY** `production:` default of the chart |
| `ingress-nginx-values-prod.yaml` | add `controller.config.use-forwarded-headers: "true"` next to the existing `lua-shared-dicts` |
| `kafka-values-prod.yaml`, `redis-values-prod.yaml` | unchanged copies |
| `opentelemetry-collector-values-prod.yaml` | unchanged copy (already points at the in-cluster Prometheus and Tempo) |
| `monitoring-stack-values-prod.yaml` | `grafana.ingress.enabled: true`, `hosts: [grafana.dc4pay.com]`, `ingressClassName: nginx`; remove any plain Grafana admin password (→ `monitoring-sops-secrets.yaml`); `prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues: false` if not already, so the ingress-nginx ServiceMonitor is picked up without the post-install `helm upgrade` the local script does |
| `tempo-values-prod.yaml`, `prometheus-kafka-exporter-values-prod.yaml`, `prometheus-postgres-exporter-values-prod.yaml` | unchanged copies |
| `argocd-values-prod.yaml` (new) | the Argo CD chart values: `helm-secrets` sidecar on the repo-server with the age key mounted — take the manifest from the plugin's **ArgoCD Integration** page for the installed version (**VERIFY**: the env names `HELM_SECRETS_*`, the `SOPS_AGE_KEY_FILE` mount path, and the allowed URI schemes); `server.ingress` for `argocd.dc4pay.com` optional; `configs.params."server.insecure": true` because TLS ends at Cloudflare |
| `cloudflared-values-prod.yaml` (new) | if a public chart is used; otherwise the tiny in-repo chart of 3.8 needs none |

Done when: `helm template` renders for each (`helm repo add` the same repos the local scripts use; versions: Keycloak `20.0.0`, Kafka `32.3.14` as in `deploy-all-external-infra-local.sh`); `grep -l local infra/helm-values/*-prod.yaml` prints nothing.

### 3.5 Realm JSON (shared artifact) — one harmless change

In `keycloak/realm/ecommerce-platform.json`, client `backoffice-ui`: `redirectUris: ["http://localhost:*", "https://backoffice.dc4pay.com/*"]`, and `attributes."post.logout.redirect.uris": "http://localhost:*##https://backoffice.dc4pay.com/*"`. Nothing else: `sslRequired`, brute force and the staff passwords are applied by `setup-keycloak-prod.sh` (3.9), so local keeps working unchanged.

Done when: `mvn -pl e2e-tests` is **not** run (long); instead `keycloak/setup-keycloak.sh` against the laptop's OrbStack still succeeds and `keycloak/get-access-token.sh user marketplace-5` still returns a token.

### 3.6 New SOPS files (laptop; the server key is already a recipient after Phase 2)

```bash
cd $REPO
sops keycloak-sops-secrets.yaml      # opens an editor on a new file; paste, save, it encrypts
```
Contents (chart key paths, plus our own block):
```yaml
auth:
  adminPassword: <generate: openssl rand -base64 24>
postgresql:
  auth:
    password: <generate>
staffPasswords:
  support-ops: <generate>
  finance-ops: <generate>
  backoffice-admin: <generate>
```
Likewise `monitoring-sops-secrets.yaml` (`grafana.adminPassword`), `backoffice-sops-secrets.yaml` (`sessionSecret`), and after Phase 5 step 2 `cloudflared-sops-secrets.yaml` (`tunnel.token`). Remove the duplicated `PAYMENT_SERVICE_POSTHOG_TRACES_API_TOKEN` from one of the two existing files (check which chart reads it from which Secret first: `grep -rn POSTHOG charts/*/templates`).

Done when: `sops -d <file>` works on the laptop **and** on the server; `git grep -l 'ENC\[AES256_GCM' -- '*.yaml'` lists every secrets file (= all encrypted); `gitleaks` pre-commit passes.

### 3.7 Dockerfiles and charts for `mor-backoffice` and `checkout-demo`

**mor-backoffice.** Today: Vite dev server on 3100 proxies `/auth` and `/api` to the Express server on 3101. In the container the Express server must serve the built page itself.

1. **VERIFY** whether `mor-backoffice/server/index.ts` already serves static files; if not, add `app.use(express.static(path.join(__dirname, '../dist')))` plus a catch-all for the SPA routes, after the `/auth` and `/api` routes
2. `mor-backoffice/Dockerfile`: multi-stage — `node:22-alpine` build (`npm ci && npm run build`), runtime stage copies `dist/` and the compiled server, `USER node`, `EXPOSE 3101`, `CMD ["node","server/dist/index.js"]` (adjust to how the server is compiled: **VERIFY** `package.json` scripts)
3. `charts/mor-backoffice/`: `Chart.yaml`, `templates/deployment.yaml` (1 replica, probes on `/auth/me` → expect 401 = alive, or add a `/healthz`), `service.yaml` (ClusterIP 3101), `ingress.yaml` (`host: backoffice.dc4pay.com`, path `/`), `configmap.yaml` with `PUBLIC_URL`, `KEYCLOAK_URL=http://keycloak.payment.svc.cluster.local:8080`, `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID`, `PAYMENT_API_BASE_URL=http://ingress-nginx-controller.ingress-controller.svc.cluster.local`, `SERVER_PORT`; `secret.yaml` with `SESSION_SECRET: {{ .Values.sessionSecret }}` (from `backoffice-sops-secrets.yaml`); `values.yaml` + `prod/values.yaml` (image `dcaglar1987/mor-backoffice:<sha>`). Remove the `kubectl` lookup path from `server/config.ts` or leave it as the fallback it already is (it only runs when `PAYMENT_API_BASE_URL` is unset)

**checkout-demo.** Same shape: page + proxy (`server.js`, 3001). **VERIFY** `checkout-demo/README.md` and `npm run setup-env` for the exact variables it writes to `.env` (Keycloak URL, payment API URL, merchant client secrets, Stripe publishable key); they become ConfigMap entries (the merchant secrets are public demo values, so a ConfigMap is acceptable; the Stripe publishable key is public by design). Ingress `host: checkout.dc4pay.com`. The proxy must serve the built page (`vite build` → static) — **VERIFY** as for the back office.

4. Add both to `build-all-images.sh` and to the CI matrix (3.10)

Done when: `docker build` succeeds for both; `docker run -p 3101:3101 -e PUBLIC_URL=http://localhost:3101 -e KEYCLOAK_URL=<laptop OrbStack keycloak> … mor-backoffice` serves the login page locally; `helm template` renders both charts.

### 3.8 Argo CD manifests and the bootstrap script

Layout:
```
infra/argocd/prod/
  root.yaml                 # app of apps → path infra/argocd/prod/apps
  apps/
    ingress-nginx.yaml  keycloak.yaml  kafka.yaml  redis.yaml  cloudflared.yaml          # wave 1
    central-db.yaml  payment-edge-cell.yaml  payment-edge-workers.yaml                   # wave 2
    payment-central-relay.yaml  payment-consumers.yaml  opentelemetry-collector.yaml
    mor-backoffice.yaml  checkout-demo.yaml
    monitoring.yaml  tempo.yaml  kafka-exporter.yaml  postgres-exporter.yaml            # wave 3
charts/cloudflared/         # tiny in-repo chart: Deployment running `cloudflared tunnel --no-autoupdate run --token $TUNNEL_TOKEN`, Secret from values, 2 replicas
infra/scripts/bootstrap-prod.sh
```

One of our charts (pattern for all five + the two Node apps):
```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: payment-edge-cell
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "2"
spec:
  project: default
  destination: { server: https://kubernetes.default.svc, namespace: payment }
  source:
    repoURL: https://github.com/dcaglar/payment-platform-for-marketplaces.git
    targetRevision: main
    path: charts/payment-edge-cell
    helm:
      releaseName: payment-edge-cell
      valueFiles:
        - values.yaml
        - prod/values.yaml
        - secrets://../../edge-cell-sops-secrets.yaml     # VERIFY the path form helm-secrets' Argo integration expects
  syncPolicy:
    automated: { prune: true, selfHeal: true }
    syncOptions: [CreateNamespace=true]
```
A third-party chart (pattern for Keycloak, Kafka, Redis, ingress-nginx, OTel, monitoring, Tempo, exporters) uses two sources — the chart from its Helm repo, the values from ours:
```yaml
spec:
  sources:
    - repoURL: https://charts.bitnami.com/bitnami
      chart: keycloak
      targetRevision: 20.0.0
      helm:
        releaseName: keycloak
        valueFiles:
          - $values/infra/helm-values/keycloak-values-prod.yaml
          - secrets://$values/keycloak-sops-secrets.yaml          # VERIFY: secrets:// combined with $values
    - repoURL: https://github.com/dcaglar/payment-platform-for-marketplaces.git
      targetRevision: main
      ref: values
```
Namespaces: `payment` (platform, Keycloak, Kafka, Redis, OTel), `ingress-controller` (ingress-nginx, cloudflared), `monitoring`. For `monitoring` (kube-prometheus-stack) add `syncOptions: [CreateNamespace=true, ServerSideApply=true]` — its CRDs exceed the annotation size limit of client-side apply, a known Argo issue. Release names and chart versions must equal the ones the local scripts use, so `how-to-start`'s object names (`prometheus-stack-grafana`, `kafka-controller-0`, `redis-master`, …) stay valid.

`infra/scripts/bootstrap-prod.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
[[ "$(kubectl config current-context)" == "m1-prod" ]] || { echo "context is not m1-prod"; exit 1; }
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl -n argocd create secret generic helm-secrets-private-keys \
  --from-file=key.txt="$HOME/.config/sops/age/keys.txt" --dry-run=client -o yaml | kubectl apply -f -
helm repo add argo https://argoproj.github.io/argo-helm && helm repo update argo
helm upgrade --install argocd argo/argo-cd -n argocd -f "$REPO_ROOT/infra/helm-values/argocd-values-prod.yaml"
kubectl -n argocd rollout status deploy/argocd-repo-server --timeout=5m
kubectl apply -f "$REPO_ROOT/infra/argocd/prod/root.yaml"
echo "Argo admin password: $(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)"
```
The repo is public, so Argo needs no credentials to read it. If it ever becomes private: a deploy key as an Argo repository secret, created in this script from SOPS.

Done when: `kubectl apply --dry-run=client -f infra/argocd/prod/root.yaml -f infra/argocd/prod/apps/` passes; `grep -rl local infra/argocd/prod infra/scripts/bootstrap-prod.sh` prints nothing; `shellcheck infra/scripts/bootstrap-prod.sh` clean.

### 3.9 `keycloak/setup-keycloak-prod.sh`

A copy of `setup-keycloak.sh` with four differences:

1. Guard: `[[ "$(kubectl config current-context)" == "m1-prod" ]]`
2. Inputs from SOPS, not literals: `ADMIN_PASSWORD="$(sops -d --extract '["auth"]["adminPassword"]' "$REPO_ROOT/keycloak-sops-secrets.yaml")"`, the three staff passwords likewise; `KEYCLOAK_URL` defaults to `http://localhost:18080` because the script runs behind `kubectl -n payment port-forward svc/keycloak 18080:80` (start it in the script, kill it on exit with `trap`)
3. After the two imports, apply the prod-only realm settings through the admin API: `PUT /admin/realms/ecommerce-platform` with `{"sslRequired":"external","bruteForceProtected":true,"failureFactor":10}`
4. Set the staff passwords: for each user `GET /admin/realms/ecommerce-platform/users?username=<u>` → id → `PUT /users/<id>/reset-password` with `{"type":"password","value":"<pw>","temporary":false}`

Done when: `bash -n` and `shellcheck` pass; running it against the laptop's OrbStack is **not** done (guard), so the first real run is Phase 4.

### 3.10 CI: build once, tag twice, bump `prod` tags

In `.github/workflows/ci.yml`, two jobs after the existing tests, `if: github.ref == 'refs/heads/main' && github.event_name == 'push'`:

**`publish-images`** (matrix over `payment-service`, `payment-edge-workers`, `payment-central-relay`, `payment-consumers`, `mor-backoffice`, `checkout-demo`): `docker/login-action` with the existing `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` secrets; `infra/scripts/build-and-push.sh <module> dcaglar1987 ${GITHUB_SHA} latest`.

**`bump-prod-tags`** (needs `publish-images`): `dorny/paths-filter` with the module → chart map —

| Paths changed | Charts bumped |
| --- | --- |
| `payment-service/**` | `payment-edge-cell` |
| `payment-edge-workers/**` | `payment-edge-workers` |
| `payment-central-relay/**` | `payment-central-relay` |
| `payment-consumers/**` | `payment-consumers` |
| `common/**`, `common-db/**`, `common-kafka/**`, `payment-domain/**`, `payment-application/**`, `payment-infrastructure/**`, `pom.xml` | all four JVM charts |
| `mor-backoffice/**` | `mor-backoffice` |
| `checkout-demo/**` | `checkout-demo` |

then for each bumped chart `yq -i '.image.tag = strenv(GITHUB_SHA)' charts/<chart>/prod/values.yaml` (**VERIFY** the key path per chart, as in 3.3), `git commit -m "prod: <charts> → ${GITHUB_SHA::7} [skip ci]"` and push with a token allowed to push to `main` (if `main` is branch-protected, use a PAT or a GitHub App token in a secret; or open a PR with `peter-evans/create-pull-request` and merge it yourself — a reasonable human gate for the first months).

Done when: a push to `main` produces, on Docker Hub, `<sha>` and `latest` tags for the changed images and exactly one bump commit touching only the expected `prod/values.yaml` files; a push touching only `docs/**` produces no images and no bump.

### Phase 3 exit check

```bash
mvn clean install -DskipITs                                   # green
grep -rl local infra/argocd/prod infra/scripts/bootstrap-prod.sh keycloak/setup-keycloak-prod.sh charts/*/prod infra/helm-values/*-prod.yaml   # nothing
infra/scripts/security-scan.sh                                # pom/Dockerfiles/charts changed
```
And `local` still works: `infra/scripts/deploy-all-local.sh` on the laptop, how-to-start section 2 passes.

---

## Phase 4 — Bootstrap the server cluster (server over Tailscale)

**Goal:** the whole platform runs on the server from git, Keycloak issues public-issuer tokens the services accept, all before anything is public.

1. `ssh dogan@m1-server`, `su - svc`, `cd ~/payment-platform && git pull`
2. `infra/scripts/bootstrap-prod.sh` → note the Argo admin password
3. Watch: `kubectl -n argocd get applications -w` (or `kubectl -n argocd port-forward svc/argocd-server 8443:443` and open https://localhost:8443 through an `ssh -L 8443:localhost:8443 m1-server` tunnel from the laptop). Wave 1, then 2, then 3 go `Synced/Healthy`
4. Keycloak content: `keycloak/setup-keycloak-prod.sh`
5. Prove the token path without Cloudflare (all on the server):
   ```bash
   kubectl -n payment port-forward svc/keycloak 18080:80 &
   TOKEN=$(curl -s -d client_id=merchant-api-MARKETPLACE-5 -d client_secret=merchant-api-MARKETPLACE-5-secret \
     -d grant_type=client_credentials http://localhost:18080/realms/ecommerce-platform/protocol/openid-connect/token | jq -r .access_token)
   echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .iss      # "https://auth.dc4pay.com/realms/ecommerce-platform"
   kubectl -n ingress-controller port-forward svc/ingress-nginx-controller 8080:80 &
   curl -s -o /dev/null -w '%{http_code}\n' -H "Host: api.dc4pay.com" -H "Authorization: Bearer $TOKEN" \
     http://localhost:8080/api/v1/balances/merchants/me                 # 200
   ```
   Then how-to-start section 2 (create + authorize) against `http://localhost:8080` with the same `Host` header: `201`, then `200 AUTHORIZED` (Stripe test mode is reached outbound from the server — the first call can take >3 s and answer `202`, as the guide says)

**Done when:** every Application `Synced/Healthy`; `kubectl get pods -A | grep -v Running | grep -v Completed` is empty; the token's `iss` is the public URL and the API answers `200`; a MARKETPLACE-5 payment reaches `SETTLED` in central-db (`kubectl -n payment exec` into central-db, `psql`, as the e2e test queries).

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Application `ComparisonError` mentioning `sops` / `age` / `secrets://` | sidecar not running, key secret missing, or wrong path form | `kubectl -n argocd logs deploy/argocd-repo-server -c helm-secrets`; check the secret `helm-secrets-private-keys`; fix the `valueFiles` path (3.8 VERIFY) |
| `ImagePullBackOff` | tag not pushed yet or `REPLACED_BY_CI` still in values | run 3.10 once (push to main), paste the sha, or push an image by hand with that tag |
| `monitoring` app `OutOfSync` with "metadata.annotations: Too long" | CRDs via client-side apply | `ServerSideApply=true` in its syncOptions |
| Keycloak pod `CrashLoopBackOff`, log "hostname" | `KC_HOSTNAME_URL` malformed or both `KC_HOSTNAME` and `_URL` set | one of them only; `https://auth.dc4pay.com` without trailing slash |
| Keycloak ready but ingress returns `404` for `Host: auth.dc4pay.com` | Bitnami ingress `hostname` not set / wrong class | `kubectl -n payment get ingress keycloak -o yaml` |
| `setup-keycloak-prod.sh` admin login fails over the port-forward with 4xx about hostname | admin API refuses a non-public host (**VERIFY**) | run it from the laptop through `https://auth.dc4pay.com` once Phase 5 is up, with a Cloudflare Access service token; or temporarily `KC_HOSTNAME_STRICT=false` is already set — check `KC_HOSTNAME_ADMIN_URL` |
| API answers `401` to every request | `iss` mismatch | decode the token (`jq .iss`) and compare with `KEYCLOAK_ISSUER_URL` in the pod env (`kubectl -n payment exec … -- env \| grep KEYCLOAK`) |
| API `401`, pod log "Couldn't retrieve remote JWK set" | `KEYCLOAK_JWK_SET_URL` wrong or Keycloak not ready | `kubectl -n payment exec … -- curl -s $KEYCLOAK_JWK_SET_URL` |
| `payment-edge-cell` pod stuck `Init` | edge-db sidecar not starting (PVC, `edgeDb` block) | `kubectl -n payment describe pod payment-edge-cell-0`; `logs -c edge-db` |
| create payment answers `503 RETRY_LATER` | Stripe unreachable from the server (egress), or `STRIPE_API_KEY` missing | `kubectl -n payment exec … -- curl -sI https://api.stripe.com`; `sops -d edge-cell-sops-secrets.yaml \| grep STRIPE` |
| Everything pending, node `MemoryPressure` | VM below 14 GB | OrbStack Settings → Resources; `kubectl describe node` |

---

## Phase 5 — Cloudflare: tunnel, DNS, Access, rate limit (laptop + Cloudflare dashboard)

**Goal:** the five public hostnames work from the internet over TLS; admin surfaces are behind Access; a payment goes end to end through `checkout.dc4pay.com`.

1. `brew install cloudflared` **on the laptop** (only as a CLI to create the tunnel; it never runs here): `cloudflared tunnel login` (browser, pick `dc4pay.com`), `cloudflared tunnel create m1-prod`
2. Token: `cloudflared tunnel token m1-prod` → `sops cloudflared-sops-secrets.yaml` with `tunnel.token: <token>`; commit, push. Argo deploys `cloudflared` (wave 1) within minutes; `kubectl -n ingress-controller logs deploy/cloudflared` shows "Registered tunnel connection" ×4
3. Public hostnames (Zero Trust dashboard → Networks → Tunnels → `m1-prod` → Public Hostname), each with service `http://ingress-nginx-controller.ingress-controller.svc.cluster.local:80`: `api`, `auth`, `checkout`, `backoffice`, `grafana` (and `argocd` if wanted). The dashboard creates the DNS CNAMEs. Under HTTP settings for each: **HTTP Host Header** left empty (keeps the original host), **No TLS Verify** irrelevant (plain HTTP to nginx)
4. SSL/TLS → Overview → **Full** is not needed (origin is HTTP via tunnel); leave "Flexible" semantics as the tunnel implies. Edge Certificates → Always Use HTTPS on
5. Access (Zero Trust → Access → Applications → Self-hosted): three apps — `auth.dc4pay.com/admin*` (**VERIFY** that `/admin` and `/admin/*` are both covered), `grafana.dc4pay.com`, `argocd.dc4pay.com`; policy *Allow* → *Emails* → yours; login method One-time PIN. **Do not** put Access on `api.` or `auth.` root: API clients and the OIDC flow cannot pass it
6. Rate limit (Security → WAF → Rate limiting rules): expression `(http.request.uri.path eq "/api/v1/payments" and http.request.method eq "POST" and http.host eq "api.dc4pay.com")`, 60 requests / 1 minute per IP, action Block for 10 minutes (**VERIFY** free-plan limits: one rule, 10 s period on some plans)
7. Test from a phone on mobile data: `https://api.dc4pay.com/healthz` → `200`; `https://auth.dc4pay.com/realms/ecommerce-platform/.well-known/openid-configuration` → JSON with `"issuer":"https://auth.dc4pay.com/realms/ecommerce-platform"`; `https://auth.dc4pay.com/admin/` → Access login page; `https://backoffice.dc4pay.com` → Log in → Keycloak → back, as `marketplace-5`; `https://checkout.dc4pay.com` → a card payment with `pm_card_visa` → AUTHORIZED; `https://grafana.dc4pay.com` → Access → Grafana login
8. Point the external uptime check at `https://api.dc4pay.com/healthz` and `https://checkout.dc4pay.com`, 5-minute interval, alert to your email/phone

**Done when:** all of step 7 passes from outside the home network; `kubectl -n ingress-controller logs deploy/ingress-nginx-controller` shows the requests with the right `Host`; the tunnel shows `HEALTHY` in the dashboard.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Cloudflare error 1033 | tunnel not connected | cloudflared pod down or token wrong: `kubectl -n ingress-controller logs deploy/cloudflared` |
| 502 Bad Gateway from Cloudflare | wrong service URL in the public hostname, or ingress-nginx down | `curl` the service URL from a pod; check the hostname's service field |
| 404 from nginx on `auth.` / `grafana.` | no Ingress with that `host`, or `ingressClassName` missing | `kubectl get ingress -A`; compare `host` spelling |
| Keycloak login page loads over http / redirect loop / "mixed content" | `use-forwarded-headers` off, or `KC_PROXY` not `edge` | 3.4 `ingress-nginx-values-prod.yaml`; `kubectl -n payment exec keycloak-0 -- env \| grep KC_` |
| Keycloak: "Invalid parameter: redirect_uri" on back-office login | redirect URI not in `backoffice-ui` (3.5) or `PUBLIC_URL` mismatch | compare the `redirect_uri` in the browser URL bar with the client's list |
| Back office: logged in, then `401` on every API call | the API rejects the token: `iss`, or the BFF forwards to the wrong `PAYMENT_API_BASE_URL` | BFF logs; decode the token |
| Browser: certificate error on a hostname | Universal SSL not yet issued (up to 15 min), or a two-level subdomain | wait; keep hostnames one level deep |
| Checkout page loads but payment fails with CORS | `checkout-demo` proxy `CORS` origin set to localhost | its `ALLOWED_ORIGIN` env → `https://checkout.dc4pay.com` |
| `/api/v1/payments` returns 429 while testing | your own rate limit | raise the threshold or whitelist your IP in the rule |
| Access page shows for `api.` | Access app with a too-wide domain pattern | restrict the Access apps to the three admin hostnames/paths |

---

## Phase 6 — Backups, Terraform (laptop + server)

**Goal:** the data survives the SSD; the Cloudflare configuration is code.

1. Backups: a `CronJob` in `ns payment` (one more Argo Application, `charts/db-backup/`) running nightly `pg_dump` of `edge-db` (via the edge-cell pod: `kubectl exec payment-edge-cell-0 -c edge-db -- pg_dump …` is not possible from inside a CronJob without RBAC, so the CronJob connects over the network to `payment-edge-cell-0.payment-edge-cell-headless:5432` with the `EDGE_DB_MAINTENANCE_*` user and to `central-db-postgresql:5432` with `CENTRAL_DB_MAINTENANCE_*`) → `gzip` → `rclone copy` to an off-box bucket (Backblaze B2 free 10 GB, or Google Drive via rclone); credentials in `backup-sops-secrets.yaml`; keep 14 days (`rclone delete --min-age 14d`)
2. **Test the restore once now**: `pg_restore` into a scratch database on the server, count rows in `payments`
3. Terraform for Cloudflare (`infra/terraform-cloudflare/`, separate from the Azure folder): provider `cloudflare/cloudflare`; resources `cloudflare_zero_trust_tunnel_cloudflared`, `cloudflare_zero_trust_tunnel_cloudflared_config` (the public hostnames), `cloudflare_record` ×5–6 (CNAMEs, proxied), `cloudflare_zero_trust_access_application` + `_policy` ×3, `cloudflare_ruleset` (rate limit). `terraform import` each existing object (ids from the dashboard/API), `terraform plan` must show **no changes** before you trust it. State: Terraform Cloud free workspace, or local state file encrypted with SOPS and committed (`terraform.tfstate` is in `.gitignore` by default — add the SOPS-encrypted copy explicitly). API token with zone + Zero Trust scopes in `terraform-sops-secrets.yaml`

**Done when:** a backup file for today exists in the bucket and restored once; `terraform plan` is clean; deleting a DNS record in the dashboard and running `terraform apply` recreates it.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `pg_dump` auth fails | maintenance user lacks `CONNECT`/`SELECT` on the DB | check the init SQL in `charts/*/templates/*-init-secret.yaml` for the maintenance role's grants |
| `terraform plan` wants to recreate the tunnel | imported the wrong resource type / id | the tunnel id from `cloudflared tunnel list`; config vs tunnel are two resources |

---

## Phase 7 — Rebuild drill (server)

**Goal:** prove the success criterion "rebuild in under an hour" instead of assuming it. Do it once right after Phase 6, then after any change to bootstrap or Argo.

1. Note the time. Take the previous night's backup as the restore point
2. OrbStack: Settings → Kubernetes → Reset cluster (or `orb delete k8s && orb start k8s` — **VERIFY** the command), `kubectl config rename-context orbstack m1-prod` again
3. `bootstrap-prod.sh` → waves 1–3 → `setup-keycloak-prod.sh`
4. Restore: `pg_restore` the two dumps into `edge-db` and `central-db` (scale the four JVM deployments to 0 while restoring, back to 1 after)
5. Phase 5 step 7 from a phone. Note the time

**Done when:** under 60 minutes wall clock, and a payment created *before* the drill is still visible in the back office. Write the measured time here: ____ min.

Full-depth variant (do once, when convenient): include Phase 1–2 from a blank macOS; the only hand steps are Setup Assistant and the Tailscale login URL.

---

## Day-2 operations

| Task | How |
| --- | --- |
| Deploy a change | merge to `main`; CI pushes `<sha>` and bumps `prod/values.yaml`; Argo syncs in ≤3 min. Watch: Argo UI or `kubectl -n argocd get app` |
| Roll back a service | Argo UI → History and rollback → previous revision; or `git revert` the bump commit (preferred: git stays the truth) |
| Change a non-secret setting | edit `charts/<chart>/prod/values.yaml` or `infra/helm-values/*-prod.yaml`, commit; Argo applies |
| Change or add a secret | laptop: `sops -i <file>`, commit, push; Argo re-renders and restarts the pods that mount it |
| Rotate the server's age key | `age-keygen` on the server; update `.sops.yaml` (replace recipient); `sops updatekeys -y` all files from the laptop; replace the `helm-secrets-private-keys` secret; restart `argocd-repo-server` |
| Rotate the tunnel token | `cloudflared tunnel token --rotate` (**VERIFY** command) → `sops -i cloudflared-sops-secrets.yaml`; cloudflared pods restart |
| Get a token for manual tests | `ssh dogan@m1-server`, `su - svc`, `kubectl -n payment port-forward svc/keycloak 18080:80 &`, `KEYCLOAK_URL=http://localhost:18080 keycloak/get-access-token.sh merchant-api MARKETPLACE-5` |
| Look at Prometheus / Alertmanager | `ssh -L 9090:localhost:9090 m1-server` then on the server `kubectl -n monitoring port-forward svc/prometheus-stack-kube-prom-prometheus 9090:9090` |
| Upgrade macOS | plan it: `kubectl cordon` is pointless on one node, so announce downtime; Software Update manually over Screen Sharing; verify Phase 1 "Done when" afterwards (updates can reset auto-login and power settings) |
| Upgrade a third-party chart | bump `targetRevision` in its Application; Argo shows the diff before you sync if you set `automated: {}` off for that app temporarily |

| Incident | First checks | Usual fix |
| --- | --- | --- |
| Uptime check: site down | can you SSH over Tailscale? If not: power/Wi‑Fi/macOS login. If yes: `kubectl get pods -A`; `kubectl -n ingress-controller logs deploy/cloudflared` | tunnel pod restart; OrbStack restart as `svc` (`orb restart`); power cycle as last resort |
| Tunnel HEALTHY, 502s | ingress-nginx or the backend pod | `kubectl -n ingress-controller logs deploy/ingress-nginx-controller --tail=100` |
| Disk full (pods evicted, Kafka down) | `df -h` on the Mac; `kubectl describe node` (DiskPressure); which PVC: `kubectl -n payment exec kafka-controller-0 -- du -sh /bitnami/kafka/data` | lower `log.retention.*` in `kafka-values-prod.yaml`; delete old Tempo blocks; never delete Postgres data |
| Keycloak login loop | `X-Forwarded-Proto` not reaching Keycloak | `use-forwarded-headers` in ingress-nginx; `KC_PROXY=edge` |
| Every API call 401 after a Keycloak restart | key rotation (new JWKS) is normal and handled; a changed `iss` is not | decode a fresh token; compare `KC_HOSTNAME_URL` and `KEYCLOAK_ISSUER_URL` |
| Argo app stuck `OutOfSync` / `Progressing` | `kubectl -n argocd describe app <name>`; hook or CRD ordering | sync with `Replace` for CRD-only changes; `ServerSideApply` for big CRDs; check sync waves |
| Argo `ComparisonError` after a secrets change | SOPS file not decryptable with the server key (recipient dropped) | `sops updatekeys`; check `.sops.yaml` still lists the server key |
| Tailscale node "expired" | key expiry re-enabled by a Tailscale change | admin console → Disable key expiry; `sudo tailscale up` again via Screen Sharing on the LAN |
| Mac rebooted and nothing came up | auto-login user not `svc`, OrbStack not a Login Item, or FileVault turned on by an update | Phase 1 "Done when" checks |
| Postgres data corrupted / accidental delete | — | Phase 7 restore steps with last night's dump; data since then is lost, which is the accepted RPO (24 h) |
