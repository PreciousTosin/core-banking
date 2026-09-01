---
status: proposed
owners:
  - platform
related_adrs:
  - ADR-0008
related_proposals:
  - architecture/proposals/README.md#full-poc-platform
---
> **Architecture state: PROPOSED — non-current.**

# Infrastructure Architecture: Single-VPS PoC on Ubuntu 24.04

Parent architecture: [Architecture entry point](../../ARCHITECTURE.md)
Summary: This document specifies the infrastructure blueprint required to deploy the Modern Core Banking PoC on a single Ubuntu 24.04 VPS. It translates the architecture decisions into concrete host requirements, container topology, storage composition, and operational runbooks that support reproducible PoC validation. Every requirement here is either traced to a section of the parent design or marked explicitly as a local decision.

Governed proposal: [Full single-VM proof-of-concept platform](../proposals/README.md#full-poc-platform). The presence of this document, infrastructure manifests, or scripts is design evidence only; it is not evidence that a deployment exists or that an acceptance profile ran.

---

## 1) Scope, constraints and non-claims

- Target host: one VPS with 4 vCPU, 8 GiB RAM and NVMe-backed storage (§21.1).
- OS: Ubuntu Server 24.04 LTS.
- Orchestrator: Docker Compose, one base file plus one overlay per declared profile — `normal`, `concurrency`, `restore` (§21.1, §21.2).
- The supported PoC is **an evidence suite executed across declared profiles**, not a claim that every service, replica and diagnostic UI runs simultaneously at peak load (§21.1).
- Swap is disabled. An evidence-producing run is valid only when swap is disabled or unused, the kernel did not invoke OOM killing unexpectedly, the configured profile stayed below its cgroup limits, and at least the declared 2,048 MiB OS/page-cache reserve remained available (§21.1).
- Public surface: the reverse proxy is the only container that publishes a host port. `api-edge` is the only application service reachable through it; everything else is private to internal container networks (§6, §17.2).
- Observability is **not optional**: an OTel Collector and Prometheus are budgeted in all three profiles (§21.1). Only Grafana and Tempo are optional, and only in the concurrency profile (§21.14).

Non-claims: no multi-region HA, no broker-availability or replicated-durability claim (one Redpanda broker, §21.7), no disaster-recovery RTO/RPO guarantee (§21.4), no production throughput or latency claim (§3.2).

---

## 2) Version pins

| Component | Pin | Source |
|---|---|---|
| Host OS | Ubuntu Server 24.04 LTS | this document |
| `funds-core` runtime | Java 25 LTS, Quarkus 3.33.3.1 LTS, Maven 3.9.16 | `docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md` |
| PostgreSQL | 18.6 | same plan document |
| Go services | one toolchain version pinned in the build, recorded in the run manifest | local decision |
| Redpanda, Temporal, Valkey, MinIO, proxy, Toxiproxy, OTel, Prometheus | pinned by **image digest** in the Compose base/overlay files | §21.1 |

The parent design is silent on language and database versions; the pins above come from the implementation plans in `docs/superpowers/plans/` and are recorded, together with every image digest, in the run manifest described in §6 below.

---

## 3) Container topology

All of the following are part of the PoC stack (§21.2, §7, §21.6, §21.11, §21.14):

**Edge**
- Reverse proxy (Caddy or Traefik) — the sole public listener; terminates public TLS.
- `api-edge` (Go) — one replica; a second only for the relevant routing test.

**Application services**
- `funds-core` (Java 25/Quarkus) — 1 replica normally, exactly 2 in the concurrency profile, 1 in restore.
- `txn-orchestrator` (Go, Temporal Go SDK) plus one or more **Temporal workers**.
- `provider-gateway` (Go) — 1 replica normally, 2 in provider-replica tests.
- `risk-engine`, `recon-engine`, `projections` (Go).

**Test-rail components**
- `provider-simulator` (Go) — deterministic external rail, no database access (§22).
- **Toxiproxy** — network fault proxy between application containers and the simulator, Valkey, Redpanda and selected non-authoritative dependencies (§22).

**Data and messaging**
- PostgreSQL 18.6 — ledger and application data.
- **PgBouncer** (or equivalent pooler) — protects the server from replica connection multiplication; no more than 60 server connections across application, Temporal, migration, proof and reserved operator pools (§21.11).
- Redpanda — single broker, `smp=1`, explicit memory/reserve/partition/segment/retention limits (§21.7).
- Temporal server — **separate PostgreSQL databases and roles** for persistence and visibility, distinct from application schemas; endpoints internal-only (§21.6).
- Valkey — 128 MiB `maxmemory`, explicit eviction policy, no authoritative state (§21.12).
- MinIO — object storage substitute for archives and reconciliation imports (§21.12).

**Jobs**
- One-shot **migration services** (Flyway) that complete before application readiness (§21.3).

**Observability**
- **OTel Collector** and **Prometheus** — present in every profile.
- **Grafana** and **Tempo** — optional; the concurrency profile disables them (with nonessential import/export jobs) before starting the second `funds-core` replica (§21.1, §21.14).

Data flow: API edge → transaction orchestrator → `funds-core` + provider gateway; journal events propagate via the transactional outbox to Redpanda; projections and reconciliation consume outbox topics; reconciliation issues commands back to `funds-core` (§6). All inter-service money mutations are commands to `funds-core`; nothing else writes the ledger schema (§7).

Scaled services set no `container_name` and bind no duplicate fixed host ports; the proxy discovers them on the internal network (§21.2).

---

## 4) Resource profiles

The tables below are the parent design's starting ceilings, reproduced exactly (§21.1). They are **not** measured promises, and the totals **include** the 2,048 MiB OS/Docker/page-cache/safety reserve row — the container fleet's own budget is the total minus 2,048 MiB.

### 4.1 Memory ceilings

| Component group | Normal demo | Concurrency/fault | Restore/replay |
|---|---:|---:|---:|
| PostgreSQL and connection pooler | 1,152 MiB | 1,152 MiB | 1,536 MiB |
| Redpanda single broker | 768 MiB | 768 MiB | 640 MiB |
| Temporal server | 640 MiB | 640 MiB | stopped |
| Java `funds-core` | 640 MiB × 1 | 448 MiB × 2 | 512 MiB × 1 |
| Go application services/workers | 896 MiB | 1,024 MiB | 768 MiB |
| Valkey | 128 MiB | 128 MiB | stopped unless required |
| MinIO | 256 MiB | 256 MiB | 256 MiB |
| Prometheus/OTel and optional UI/traces | 640 MiB | 320 MiB | 320 MiB |
| Reverse proxy, Toxiproxy and simulator | 192 MiB | 256 MiB | 64 MiB |
| OS, Docker, filesystem cache and safety reserve | 2,048 MiB | 2,048 MiB | 2,048 MiB |
| **Planned ceiling** | **7,360 MiB** | **7,488 MiB** | **6,144 MiB** |

### 4.2 CPU quotas

| Component group | Normal demo CPU | Concurrency/fault CPU | Restore/replay CPU |
|---|---:|---:|---:|
| PostgreSQL and pooler | 0.75 | 0.80 | 1.50 |
| Redpanda (`smp=1`) | 0.75 | 0.60 | 0.40 |
| Temporal server | 0.35 | 0.25 | stopped |
| Java `funds-core` | 0.60 × 1 | 0.50 × 2 | 0.25 × 1 |
| Go application services/workers | 0.75 | 0.70 | 0.90 |
| Valkey and MinIO | 0.10 | 0.10 | 0.20 |
| Observability | 0.20 | 0.10 | 0.10 |
| Reverse proxy, Toxiproxy and simulator | 0.10 | 0.20 | 0.05 |
| **Container quota total** | **3.60** | **3.75** | **3.40** |

CPU quotas are profile inputs because scheduler starvation can create false provider timeouts; the 4-vCPU allocation deliberately leaves unallocated capacity for the kernel and Docker. CPU throttled time, runnable-queue delay and CPU pressure-stall data are recorded alongside memory (§21.1).

### 4.3 Per-container controls declared in the overlays

- PIDs limit: 256 for Java containers, 128 for Go application containers, explicit per image for infrastructure containers (§21.1).
- Open-file limit, database connections, queue concurrency and volume quota per container (§21.1).
- Java: `-Xms128m -Xmx384m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=64m -Xss512k` for the 640 MiB normal container; 256 MiB maximum heap and proportionally smaller direct memory for the 448 MiB concurrency replicas (§21.9).
- Go: `GOMEMLIMIT` at 85–90% of the cgroup hard limit, `GOGC` at the runtime default until measurements say otherwise (§21.10).
- PostgreSQL: ~256 MiB `shared_buffers`, 4 MiB default `work_mem`, bounded maintenance memory and parallel workers (§21.11).

If measured steady-state or peak RSS, or CPU pressure, does not fit, the design fails ACC-25. The remedy is to reduce scope or increase RAM/CPU — never to enable swap or conceal a component (§21.1).

---

## 5) Host preparation

`infrastructure/scripts/bootstrap-ubuntu24-poc.sh` performs all of this. It is idempotent, logs to `/var/log/core-banking/bootstrap-<ts>.log`, writes a run manifest to `/var/lib/core-banking/evidence/bootstrap-<ts>.json`, and takes its **last** destructive step (swap removal) only after every install, checkout and secret step has succeeded.

"Last", not "only": earlier steps also change the host — the `apt-daily` timers *and services* and `unattended-upgrades` are masked, packages that conflict with Docker Engine (`docker.io`, `docker-compose`, `docker-buildx`, `containerd`, `runc`, …) are removed, and `ufw` is enabled — all before the Compose files and secrets are validated. That order is deliberate: resolving the checkout needs `git`, which the script installs, so a host without `git` would otherwise fail with `git: command not found` instead of a clear "missing Compose file" error.

The apt freeze is the **first** thing done after the apt environment is configured, before any `apt-get` call. `-o DPkg::Lock::Timeout` covers the dpkg *frontend* lock used by `apt-get install`; it does **not** cover the apt *lists* lock, so with `/var/lib/apt/lists/lock` held `apt-get update` fails immediately (rc=100, under a second) no matter how large the timeout is. On a freshly booted cloud image the holders are exactly `apt-daily.service`, `apt-daily-upgrade.service`, `unattended-upgrades.service` and cloud-init, so they are masked first, cloud-init is given a bounded wait, `dpkg --configure -a` repairs anything a stopped upgrade left half-configured, and every `apt-get update` is retried (10 attempts, 15 s apart) before the run is failed.

The step order in `main()` is: preflight → time sync → apt environment → **freeze background apt/cloud-init** → remove conflicting packages → base packages → Docker Engine → `daemon.json` → **re-check storage against Docker's data root** → sysctl → firewall → resolve checkout → resolve Compose files → secrets → **run manifest (the `docker compose config` gate)** → disable swap → refresh the manifest's swap state → drain and start the profile → post-check.

| Host item | Setting | Origin |
|---|---|---|
| Container log rotation | `json-file` driver, `max-size=32m`, `max-file=5` in `/etc/docker/daemon.json` | **parent-mandated** (§21.14: logs rotate by size/time under a disk quota) |
| `daemon.json` handling | the script **merges** its keys into any existing `/etc/docker/daemon.json` with `jq` (its keys win, everything else — `data-root`, registry mirrors, an existing `ip6tables` setting — is preserved) and refuses to touch a file that is not valid JSON | **local decision**: overwriting the file would break the "re-running converges the host" property and could silently undo provider defaults |
| Swap | `swapoff -a`, systemd `.swap`/zram units masked (both `systemd-zram-setup@*` and `zram-tools`' `zramswap.service`); `/etc/fstab` amended only with `--persist-swap-off`. Swap is disabled on **every** run, including `--allow-undersized` ones: on a memory-tight host with swap genuinely in use, `swapoff -a` must fault every swapped page back into RAM, so it either fails (aborting the run) or invokes the OOM killer. Free memory, or `swapoff` by hand, before bootstrapping such a host | **parent-mandated** (§21.1) |
| Free disk | at least **40 GiB** free on the filesystem that holds Docker's data root — `/var/lib/docker` when it already exists, otherwise `/var/lib` — and re-checked against `docker info --format '{{.DockerRootDir}}'` once Docker is installed | **local decision**: the parent names no figure; 40 GiB is sized for PostgreSQL data + WAL, Redpanda segments, MinIO archives, images and a restore drill on one host. The re-check exists because volumes live under the data root, which providers commonly mount separately and which a pre-existing `daemon.json` may relocate |
| Volume byte quotas | preflight **warns** when the filesystem backing Docker's data root (same path as the row above) is not mounted with **project** quotas — `prjquota`/`pquota` only; user (`quota`, `usrquota`) and group (`grpquota`) quotas do not satisfy it, because they give a Docker `local` volume no byte ceiling | **derived requirement** for §21.4 (see §8): Docker `local` volumes cannot enforce a byte quota without `prjquota` or a per-store loopback image |
| Evidence prerequisites | `sysstat` installed; host/run manifest emitted per run | **parent-mandated** (§21.16 records VM/kernel/storage details, image digests, configuration hashes) |
| PIDs and open-file limits | declared per container in the overlays; the host sets a `nofile` default-ulimit of 65536 as a floor | §21.1 mandates the per-container limits; the host default-ulimit is a **local decision** |
| `fs.aio-max-nr = 1048576` | `/etc/sysctl.d/99-core-banking-poc.conf` | **derived requirement**: Redpanda/Seastar needs it and Ubuntu's default is 65536, so §21.7 and ACC-35 fail without it. The parent does not name the sysctl. |
| `fs.file-max`, `net.core.somaxconn`, `fs.inotify.max_user_instances`, `fs.inotify.max_user_watches` | same file | **local decision** (headroom for the container fleet) |
| Docker `live-restore` | `/etc/docker/daemon.json` | **local decision** (a dockerd restart should not take the stack down mid-run) |
| Docker apt signing key | pinned to primary fingerprint `9DC858229FC7DD38854AE2D88D81803C0EBFCD88` before it is installed into `/etc/apt/keyrings/docker.asc`; the run fails if it does not match | **local decision**: the key is fetched over TLS from `download.docker.com`, which authenticates the host but not the key. The pin is a tripwire against a substituted key on a mirror, proxy or captive portal — apt would otherwise accept anything that key signs. Docker publishes no fingerprint outside the key itself, so this is a substitution tripwire, not out-of-band trust |
| Firewall | `ufw` default-deny inbound, allowing tcp/80, tcp/443 and the **union of `ssh.socket`'s `ListenStream` ports and the ports `sshd -T` reports** (fallback tcp/22; tokens that are not valid TCP port numbers are warned about and skipped, and tcp/22 is opened when the probe yields no usable port at all, so `ufw --force enable` can never leave the host with no SSH rule); plus a `DOCKER-USER` chain that drops new inbound traffic on the default-route interface except tcp/80 and tcp/443, installed under `iptables` and mirrored into `ip6tables` **when that chain set exists** (a warning otherwise, see §9). After installing the chain the script asserts that `FORWARD` actually jumps to `DOCKER-USER` — fatal for IPv4, a warning for IPv6 | §17.2 mandates the boundary; **the mechanism is a local decision**. The `DOCKER-USER` hook is required because Docker's published ports bypass `ufw`. The SSH port set is derived rather than hardcoded so a host hardened out of band (see the SSH row) is not locked out for new connections the moment `ufw` is enabled. The `FORWARD` assertion catches the case where dockerd installs no jump at all — `"iptables": false` in a pre-existing `daemon.json` (which the merge deliberately preserves) or a dockerd on the nftables firewall backend — in which case the chain would exist, look correct and filter nothing |
| Unattended upgrades | `apt-daily.timer`, `apt-daily-upgrade.timer`, `apt-daily.service`, `apt-daily-upgrade.service` and `unattended-upgrades.service` disabled and masked before the first `apt-get` call; `APT::Periodic` switched off; a bounded, non-fatal `cloud-init status --wait` where cloud-init is present | **local decision** serving §21.16 (a background dockerd restart or reboot invalidates an evidence run). The `.service` units are masked as well as their timers because masking a timer only stops *future* triggers — a job already running keeps its apt locks |
| Time synchronisation | `systemd-timesyncd` enabled; the script asserts `timedatectl` reports NTP-synchronised and fails otherwise | **local decision** serving §8.8 (bitemporal records) and §21.16 (evidence timestamps). `chrony` is an acceptable substitute. |
| Operator identity | provisioning and Compose run as `root` | **local PoC decision**. The parent has no host user model. `apt`, `sysctl`, `swapoff` and `ufw` all require root, and adding a non-root user to the `docker` group would be root-equivalent rather than least privilege, so it is not done. Least privilege is enforced *inside* the stack: distinct service identities and database roles (§17.1), and application database roles that cannot alter journal facts, disable invariant triggers or impersonate an auditor (§17.7). |
| SSH | key-only authentication, `PermitRootLogin prohibit-password`, password authentication disabled | **local decision**; not automated by the bootstrap script — apply it in the provider image or by configuration management before exposing the host. Note that openssh-server on 24.04 is **socket-activated**: `sshd-socket-generator` normally propagates `Port`/`ListenAddress` from `sshd_config` into `ssh.socket.d`, so the two agree — but an operator who edits `ssh.socket` directly moves the listener while `sshd -T` still reports 22, which is why the firewall opens the union of both (see the Firewall row) |

Firewall rules installed in `DOCKER-USER` are not persisted across reboot; re-run the bootstrap script after a reboot (it is idempotent). **A re-run is the whole pipeline, not a firewall repair**: it re-runs `apt-get install docker-ce …` with **no version pin** (so Docker Engine and the Compose plugin can move to a newer version part-way through a multi-profile campaign — each manifest self-describes, but the campaign is then no longer on one engine), it **drains and restarts the running profile** (`down --remove-orphans`, then `up -d --wait`), and it writes a new manifest and a new before/after component-set pair. It also briefly empties the `CORE-BANKING` chain while refilling it. Re-run in a maintenance window and file the result as a **new run**, not a repair.

To restore only the `DOCKER-USER` rules without restarting the stack, re-apply them by hand — this is what the script installs, for `iptables` and, where the chain set exists, `ip6tables`:

```
iptables -N CORE-BANKING 2>/dev/null; iptables -F CORE-BANKING
iptables -A CORE-BANKING -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
iptables -A CORE-BANKING ! -i <public-if> -j RETURN
iptables -A CORE-BANKING -p tcp -m multiport --dports 80,443 -j RETURN
iptables -A CORE-BANKING -j DROP
iptables -C DOCKER-USER -j CORE-BANKING 2>/dev/null || iptables -I DOCKER-USER 1 -j CORE-BANKING
```

`<public-if>` is the `dev` field of `ip -o route show default`.

The interface the chain is anchored on is taken from the `dev` field of `ip -o route show default`, not from a fixed column: `default dev eth0 scope link`, `default nhid 42 via … dev eth0 …` and multipath `nexthop via … dev eth0` all shift the column, and a chain anchored on a non-interface silently matches nothing. If the parsed name is not present in `/sys/class/net` the script fails rather than installing a chain that filters nothing.

### 5.1 Bootstrap script interface

```
sudo ./infrastructure/scripts/bootstrap-ubuntu24-poc.sh [options]

  --profile <normal|concurrency|restore>   profile to start (default: normal)
  --repo <url> / --branch <name>           managed clone source (default: the
                                           GitHub remote, branch master)
  --repo-dir <path>                        checkout to deploy
  --secrets-dir <path>                     Docker secret material (default /etc/core-banking/secrets);
                                           exported to Compose as CB_SECRETS_DIR (§10)
  --wait-timeout <seconds>                 readiness timeout for `up --wait`, a positive
                                           integer 1-999999 (default 300); `0` is rejected
                                           because Compose reads it as *no timeout*
  --persist-swap-off                       also comment out swap entries in /etc/fstab
  --skip-secrets                           development only
  --allow-undersized                       downgrade CPU/RAM/disk preflight to warnings;
                                           such a run cannot produce ACC-25 evidence
  --allow-missing-healthchecks             downgrade the §6 "every long-running service
                                           declares a healthcheck" contract to a warning
  --verbose                                trace execution
```

Repository resolution: if the script is executed from inside a checkout — including one owned by the login user, which `sudo` runs as root and which is detected as a checkout rather than mistaken for "no checkout" — or `--repo-dir` names an existing checkout (a linked worktree or submodule counts: there `.git` is a `gitdir:` *file*, not a directory), that tree is deployed **in place** with no clone and no fetch, so local edits are what runs. Otherwise the script clones `--repo`/`--branch` into `/opt/core-banking` and, on re-runs, **resets that managed clone to `origin/<branch>`** (`git fetch --prune` then `git checkout -B <branch> origin/<branch>`). This is a hard reset of the branch ref: any commit made directly in the managed clone is discarded. The managed clone is a deployment target, not a place to work — edit in a real checkout and deploy it with `--repo-dir`. If the managed-clone target exists, is non-empty and is not a checkout, the script fails naming the path rather than letting `git clone` refuse it.

Preflight refuses to continue on anything other than Ubuntu 24.04 on amd64/arm64 with cgroup v2, and (unless `--allow-undersized`) fewer than 4 vCPU, less RAM than the selected profile's planned ceiling in §4.1 — 7,360 MiB for `normal`, 7,488 MiB for `concurrency`, 6,144 MiB for `restore` — or less than 40 GiB free on the filesystem holding Docker's data root. A run started with `--allow-undersized`, `--skip-secrets` or `--allow-missing-healthchecks` records that fact in the manifest (§6), because such a run cannot produce ACC-25 evidence.

---

## 6) Compose layout, project name and configuration hash

```
infrastructure/compose/docker-compose.yml                 # base: networks, volumes, secrets, common services
infrastructure/compose/docker-compose.normal.yml          # overlay
infrastructure/compose/docker-compose.concurrency.yml     # overlay
infrastructure/compose/docker-compose.restore.yml         # overlay
```

Invocation is always base **plus exactly one** overlay under a fixed project name:

```
docker compose --project-name corebank \
  -f infrastructure/compose/docker-compose.yml \
  -f infrastructure/compose/docker-compose.<profile>.yml <command>
```

The fixed project name is load-bearing: without it, starting a second profile creates a second, independently named project and both profiles' containers run simultaneously on one 8 GiB host, silently invalidating every resource measurement. The bootstrap script never substitutes a different topology — if either file is missing it fails with the exact paths it expected.

Each overlay must list **every active container** with its image digest, CPU quota, memory limit and reservation, PIDs limit, open-file limit, database connections, queue concurrency and volume quota (§21.1). Images are pinned by digest, not by tag: the bootstrap script reads `docker compose … --profile '*' config --images` and **fails the run** if any entry lacks `@sha256:`, naming the offending images. It also fails if that command fails or returns an empty list, rather than recording `"images": []` as evidence. `--profile '*'` is what makes the gate complete: a service carrying a compose `profiles:` key — the sanctioned shape for the Flyway one-shots below — is otherwise invisible to `config`, so an unpinned migration image would pass unseen. (`--resolve-image-digests` is deliberately not used: it is a no-op when combined with `--images`, and on its own it performs a live registry lookup, which would make manifest writing fail on an unreachable or private registry.)

**Contract that the base file and every overlay must satisfy** — the script cannot create these files, and `docker compose up --wait` is only meaningful when they hold:

- **Every long-running service declares a `healthcheck:`.** `--wait` treats a service *without* one as ready the instant its container is running, so an unhealthy stack would pass silently. The post-check **enforces** this: it fails the run, naming them, when any running project container declares no `CMD`/`CMD-SHELL` healthcheck, and it separately fails when a container that does declare one is not `(healthy)`. A shell-less image is not an excuse — the `CMD` form runs a binary from the image directly (Toxiproxy, for instance, ships `/toxiproxy-cli`). `--allow-missing-healthchecks` downgrades the first check to a warning for a non-evidence run and is recorded in the manifest.
- **Every one-shot service** (the Flyway migration jobs of §21.3) is either declared as a `depends_on: {…: {condition: service_completed_successfully}}` dependency of a long-running service, or carries a compose `profiles:` key and is executed with `docker compose … run --rm <job>` **before the bootstrap script is invoked — the script never runs it, and its post-check cannot see that it was skipped** (see §7 step 2). A one-shot service that is neither makes `up --wait` exit non-zero the moment the job exits 0, aborting the run. Wired as a completion dependency, the job's `Exited (0)` container is expected and the post-check only notes it.
- **Networks are IPv4-only** — no `enable_ipv6` (see §9).
- **Secret file paths interpolate `CB_SECRETS_DIR`** (see §10).

Every run records, in `/var/lib/core-banking/evidence/bootstrap-<ts>.json`: profile, project, repository directory, branch and commit, the exact `-f` file list, the **sha256 of `docker compose config --no-path-resolution`**, the resolved image list, kernel, OS, CPU model, vCPU count, MemTotal, Docker's data root and the free disk on it, cgroup version, Docker and Compose versions, swap devices, NTP sync state, and the run's degradation flags (`wait_timeout`, `allow_undersized`, `allow_missing_healthchecks`, `skip_secrets`, `persist_swap_off`). **ACC-25 fails if a report cannot identify the exact overlay/configuration hash** (§21.1), so this hash is attached to every resource report (§21.16).

`--no-path-resolution` is what makes the hash an identity: plain `config` rewrites relative bind-mount paths to absolute host paths, so the same overlay hashes differently from `/opt/core-banking` than from a developer checkout. The flag is used **only** for the hash — never for the actual `up`, where bind mounts must still resolve.

Both `config` invocations (the hash and the digest gate) are run **from the compose directory**, with relative `-f` arguments, in a subshell. Under `--no-path-resolution` Compose stops resolving a relative `env_file:` against the project directory and reads it relative to the *invoking process's* current directory, so running the hash gate from anywhere else — the repo root, `/`, wherever the operator happened to be — aborts a perfectly startable stack with the misleading message "the profile overlay is not valid". (`--project-directory` does not fix this; only the `cd` does. Measured on Compose v5.5.0.) The digest gate does not pass the flag and is cwd-independent; it shares the `cd` only so both gates use one invocation shape. Two consequences to record with the evidence:

- the hash is **path-independent with respect to the checkout** — the same tree at two different absolute paths produces the same sha256 — but **not with respect to `--secrets-dir`**: the script exports it as `CB_SECRETS_DIR` before the gate, the base file interpolates it into the `secrets: file:` paths (§10, and the `CB_SECRETS_DIR` bullet above), and `--no-path-resolution` does not touch an already-absolute interpolated value. The same tree therefore hashes differently under a non-default `--secrets-dir`. Keep `--secrets-dir` at its default on evidence hosts, or record it beside the hash;
- once the env files load, their **values are inlined** into the rendered config, so the identity hash moves when an env-file value changes. That is the intended behaviour — the value is part of the configuration — but it means the hash is not comparable across environments that differ only by env file.

The identity hash is the **as-invoked** set (base + the one overlay, no `--profile` flag). `--profile '*'` is applied to the digest gate only: adding it to the hash would change every previously recorded ACC-25 hash, and the hash is meant to identify what was actually started.

The post-check does not trust `up --wait` on its own. It takes **two samples of every project container about 12 seconds apart** and fails the run when a container is `restarting` at either sample, when its `RestartCount` grew between them, when its exit code is non-zero, when it is `dead`/`created`, or when `docker inspect` can no longer read it at either sample (it was removed, or dockerd became unreachable, mid-check). A service with a `restart:` policy and no healthcheck can crash-loop while `--wait` reports it "Healthy" — it is momentarily running at every instant — and a container that exits shortly *after* `up` returns is invisible to a single read. `RestartCount > 0` on its own is deliberately **not** a failure: a service that restarts once while a dependency comes up is legitimate. An `Exited (0)` one-shot job stays a note, per the contract above.

The component set before and after each transition is written to `bootstrap-<ts>-before.txt` and `bootstrap-<ts>-after.txt`. Both are taken over *all* containers of the project (`docker ps -a` / `compose ps -a`) so that completed one-shot jobs appear on both sides and the two sets are comparable.

---

## 7) Profile switching procedure

Profile transitions drain traffic and workers, record the before/after component set, then start the next profile. They never kill an in-flight external submission merely to reclaim memory (§21.3). Switching is scripted and recorded in each test artifact, and is never used to imply multi-host availability (§21.1).

1. Quiesce external submission (stop the load generator; let in-flight provider attempts reach a terminal or recorded-indeterminate state).
2. Run the transition:
   ```
   sudo ./infrastructure/scripts/bootstrap-ubuntu24-poc.sh --profile concurrency
   ```
   which, in order: validates the Compose files and **writes the run manifest** (this doubles as the `docker compose config` gate, so an invalid overlay fails **before the swap step** — before anything irreversible, though by then the host has already had its apt timers frozen, conflicting packages removed and `ufw` enabled, see §5) → disables swap and rewrites the manifest's `swap_devices` field with the post-`swapoff` state → records the current component set → `docker compose --project-name corebank down --remove-orphans` (drain) → `docker compose … up -d --remove-orphans --pull missing --wait --wait-timeout 300` → post-check.

   The scripted transition performs **no** `compose run` and passes **no** `--profile` to `up`, and it offers **no hook between its drain and its `up`** — the two are consecutive statements of one function, so there is no moment during a run at which the operator can interpose a job. If a one-shot job uses the `profiles:` shape of §6 rather than a `service_completed_successfully` dependency, `up` will not start it, no container is created, and the post-check — which lists the project's containers — cannot see that it is missing.

   **Prefer the dependency shape**: it is the only one the scripted transition carries out end to end. If the job must keep its `profiles:` key, run it **before invoking the script**, against the profile that is still running:
   ```
   docker compose --project-name corebank \
     -f infrastructure/compose/docker-compose.yml \
     -f infrastructure/compose/docker-compose.<profile>.yml run --rm <job>
   sudo ./infrastructure/scripts/bootstrap-ubuntu24-poc.sh --profile <profile>
   ```
   (`run` enables the target service's own profile; no `--profile` flag is needed.) The script's drain then removes the containers `run` started, but `down` without `-v` preserves the named volumes (see below), so the migration survives into the profile the script starts. A forgotten `profiles:` job yields a run that passes the post-check with its migrations unapplied.
3. Confirm readiness and that swap is still off. `--wait` returns non-zero when a service that **declares a healthcheck** fails it within the timeout; a service without one is reported ready as soon as it is running, which is why §6 requires every long-running service to declare a healthcheck, why the post-check fails a run that has running containers without one, and why it re-asserts `(healthy)` for those that do. The post-check additionally samples every container twice (~12 s apart) and fails on crash loops and non-zero exits that `--wait` cannot see (§6).
4. File the manifest, the before/after component sets and the configuration hash with the test artifact.

Because the drain always targets the fixed `corebank` project, a switch replaces the running profile rather than stacking on top of it. If the drain fails, the script stops there rather than starting the next profile on top of a half-drained one.

**The drain preserves named volumes deliberately** (`down` without `-v`): a `normal` → `concurrency` switch must not destroy the ledger. A **restore drill therefore needs one manual step**, because §8 requires restore onto *empty* volumes:

```
docker compose --project-name corebank down --remove-orphans
docker volume ls --filter label=com.docker.compose.project=corebank      # confirm the list first
docker volume rm corebank_pgdata corebank_pgwal corebank_redpanda …      # the stores being restored
sudo ./infrastructure/scripts/bootstrap-ubuntu24-poc.sh --profile restore
```

This is destructive and is never done by the script. Take the verified base backup and the recovery-checkpoint manifest (§8) first.

---

## 8) Storage and backup

**On-host volumes.** PostgreSQL/WAL, Redpanda, Temporal and MinIO use **separate volume paths and byte quotas**, so one retention leak cannot consume all storage (§21.4). Disk policy reserves the greater of 20% of the volume or the measured space required for checkpoint/WAL recovery.

**Byte quotas need a mechanism; Docker alone has none.** A Docker `local` volume ignores a size request unless the backing filesystem supports project quotas — `docker volume create --opt size=…` on a plain ext4/overlay host fails with `quota size requested but no quota support`. One of the following must be chosen and recorded with the run (**local decision**, serving §21.4):

- **XFS or ext4 with `prjquota`** on the filesystem holding `/var/lib/docker`, plus `driver_opts: {type: none, o: bind, device: …}` per store on a project-quota-managed directory (or `size=` on XFS); or
- **a per-store loopback image** — `truncate -s 8G /var/lib/core-banking/stores/pg.img`, `mkfs.ext4`, mount it, and bind the volume to the mount point. Slower, but works on any provider image and gives a hard byte ceiling per store.

The bootstrap preflight **warns** when the filesystem backing Docker's data root carries no *project* quota mount option (`prjquota`/`pquota`; user and group quotas do not count, since neither gives a `local` volume a byte ceiling), so an overlay that declares volume quotas is not silently unenforced. The check runs twice — once in preflight against `/var/lib/docker` or `/var/lib`, and again after Docker is installed against the data root the daemon actually reports.

**Thresholds are monotonic** — warning < page < stop-import < stop-money (§21.4). At the final safety threshold, new financial commands are rejected *before acceptance* while status queries, reconciliation and drain operations retain reserved connections and disk (§21.4, §21.15). Load-shedding order is: optional analytics/export, projection catch-up, reconciliation imports outside cutoff, new nonfinancial requests, then new financial commands affected by the exhausted dependency (§21.15).

**Off-host, encrypted object storage** receives (§21.4):
- the PostgreSQL base backup and WAL archive, or an equivalently tested PITR stream;
- signed journal integrity roots and manifests;
- exported reconciliation evidence required for restore exercises.

**MinIO on this host is a functional object-store substitute, not a backup failure domain** (§21.4). Snapshotting MinIO locally does not satisfy the backup requirement.

A verified base backup is taken before each tagged demonstration and WAL is archived off-host continuously during the evidence run. A **recovery-checkpoint manifest** records last journal sequence, last retained outbox sequence, database timeline, backup/WAL hashes, signed-root sequence, schema/image versions and encryption-key ID.

**Restore is performed onto empty volumes and rejects missing, corrupt or hash-mismatched artifacts.** Encryption-key recovery is tested from a separately protected copy. Restore duration is measured, but the PoC makes no RTO or RPO guarantee (§21.4).

Emptying the volumes is a **manual, deliberate step**: the scripted profile transition runs `down` *without* `-v`, so named volumes survive every ordinary switch. Remove the stores under test with `docker volume rm` after the drain and before starting the `restore` profile — the exact sequence is in §7.

Retention: Redpanda topic retention, retry/DLQ bytes and maximum partitions are sized from the test matrix; Temporal history and visibility retention are capped; Prometheus uses short PoC retention with a storage-size cap (§21.13, §21.14).

---

## 9) Networking and security posture

- **Public surface.** The reverse proxy is the only container that publishes host ports (tcp/80, tcp/443) and the only public TLS terminator. `api-edge` is the only application service it routes to. Databases, brokers, caches, object storage, Temporal, Toxiproxy, the simulator and all administrative and metrics endpoints are private to internal container networks and publish no host ports (§6, §17.2).
- **Proxy → api-edge.** The hop from the proxy to `api-edge` uses mTLS with identities issued by the local PoC CA; the proxy does not hand plaintext to the edge (§17.7). Service-to-service HTTP/gRPC likewise uses mTLS identities from that CA. Network location alone is not identity (§17.7).
- **Datastore transports.** PostgreSQL, Redpanda, Temporal, Valkey and MinIO all enable their supported **authenticated, encrypted** transports (§17.7). "Internal network" is not an accepted substitute.
- **Provider webhooks** terminate at a dedicated authenticated route with strict request limits (§17.2).
- **Identity.** Channels authenticate through an OIDC-compatible provider; a local OIDC provider is acceptable for the PoC but issuer, audience, signature algorithm, expiry/not-before, subject and required assurance/role claims are all validated. Administrative sessions are short-lived and cannot use customer-channel tokens (§17.1, §17.7). Authorisation is deny-by-default; privileged actions require maker-checker with different subjects and hash-bound approval (§17.1, §17.7).
- **Host firewall.** `ufw` default-deny inbound with tcp/80, tcp/443 and the configured SSH port allowed, plus a `DOCKER-USER` chain that drops other new inbound traffic on the public interface, because published container ports bypass `ufw` (see §5).
- **IPv4-only container networks.** Every Compose network in this PoC is IPv4-only; no network sets `enable_ipv6`. This is load-bearing: with the default IPv4-only network, a v6 connection to a published port terminates at `docker-proxy` through the `INPUT` chain, where `ufw`'s default-deny applies. A network with `enable_ipv6` instead gets an `ip6tables` NAT/DNAT path into `FORWARD`, which the IPv4 `DOCKER-USER` chain does not see. The bootstrap script therefore mirrors the `CORE-BANKING` chain into `ip6tables` as well when that chain set exists, and warns when it does not — but the primary control is that the overlays declare no IPv6 network.
- **DNS and certificates.** The demonstration hostname resolves to the VPS; the proxy obtains a public certificate via ACME (HTTP-01 on tcp/80, which is why tcp/80 is open). Where the host has no public DNS name, the proxy uses a certificate from the local PoC CA and clients are configured to trust it. Either way, the internal PoC CA is used for mTLS, never for the public edge on a public name.
- **SSH.** Key-only authentication, no password authentication, `PermitRootLogin prohibit-password`. Applied out of band (see §5).
- **Data protection.** Sensitive fields are encrypted at rest with separable keys and account identifiers are masked in logs and interfaces; BVN, NIN, PAN, customer names and full account numbers never appear in metric labels, traces, event headers or Temporal search attributes (§17.4). Test data remains synthetic (§17.7).
- **Compliance boundary.** The PoC excludes production-grade KYC/AML and high-trust regulatory commitments; the governing privacy baseline and its boundaries are stated in §17.4 and §14.3 of the parent.

---

## 10) Secrets

"No secrets manager" is not an allowed mode (§17.3). This PoC uses the first of the two sanctioned options: **SOPS-encrypted configuration plus Docker secrets** (§17.3, §21.5).

- Working credentials are never committed, baked into images or printed in logs. The repository contains SOPS-encrypted material and generation instructions only (§21.5).
- The Compose base file declares `secrets:` entries backed by files in the secrets directory (mode `0700`, files `0600`), which defaults to `/etc/core-banking/secrets`. Containers consume them as mounted secret files, never as environment variables (§21.17).
- **The base file must interpolate the directory, not hardcode it.** The bootstrap script exports `CB_SECRETS_DIR` (the value of `--secrets-dir`) before it invokes Compose, so every `secrets:` entry reads:
  ```yaml
  secrets:
    postgres_password:
      file: ${CB_SECRETS_DIR:-/etc/core-banking/secrets}/postgres_password
  ```
  Without this, `--secrets-dir /elsewhere` would populate a directory the stack never reads and the run would either fail at `up` or silently use stale files at the default path.
- **Naming convention: one encrypted file per secret value.** A committed file `infrastructure/secrets/<secret>.enc` decrypts to `<secrets-dir>/<secret>` — `postgres_password.enc` → `postgres_password`. A single encrypted YAML document holding many values (`*.enc.yaml`, `*.enc.yml`) is *not* a Docker secret file; the script skips such files with a warning rather than dropping a whole document where one credential is expected. Split them into one `<secret>.enc` per value.
- Bootstrap procedure: `bootstrap-ubuntu24-poc.sh` creates the directory, and if `infrastructure/secrets/*.enc` exists and `sops` is installed it decrypts each file into that directory under `umask 077`. Decryption goes to a temporary file and is installed at mode `0600` only on success, so a failed decrypt (missing age/KMS key) never leaves an empty file behind; the "do we have secrets?" gate ignores zero-byte files for the same reason. If no non-empty secret material is present the script **fails with generation instructions** rather than starting an unauthenticated stack. `--skip-secrets` exists for throwaway development runs only, and is recorded in the manifest.
- Generating a value by hand, for a store that does not have a committed encrypted template yet:
  ```
  sudo install -d -m 0700 /etc/core-banking/secrets
  sudo bash -c 'umask 077; openssl rand -base64 32 > /etc/core-banking/secrets/postgres_password'
  ```
  The whole subshell runs under `sudo`: `sudo` resets the umask to the sudoers default (0022), so `( umask 077; … | sudo tee … )` would create a world-readable file.
- Rotation and exposure are covered by the signing-key-rotation and secret-exposure runbooks (§18.4, and §11 below).

---

## 11) Observability, health and runbooks

**Telemetry.** OpenTelemetry provides traces and metrics through the **OTel Collector**; Prometheus scrapes and retains them. Logs are structured JSON with correlation IDs on stdout, rotated by the Docker `json-file` driver under a size cap. Telemetry redaction is tested (§18.1, §21.14, §21.17). Metric labels use controlled low-cardinality values; customer, account, transaction, provider-reference and free-text error values are forbidden labels (§21.14). Trace sampling is deterministic by non-sensitive correlation hash, with forced sampling for named failure classes. When the collector is unavailable, services retain only a bounded buffer and drop non-audit diagnostics with a counter; financial and privileged audit evidence follows its durable off-host path and is not treated as ordinary logs (§21.14).

Grafana and Tempo are optional and are disabled in the concurrency profile; dashboards can be queried from retained Prometheus metrics after the run (§21.14).

The required business and resource metrics are enumerated in §18.2 of the parent and are a precondition for the §21.16 tuning loop.

**Health endpoints (§18.3).**
- `/health` — liveness: reports whether the process can execute.
- `/ready` — readiness: reports whether the process can *safely receive its class of work*.

Readiness does not claim that all dependencies will stay available. Services implement bounded connection retries, backpressure and graceful degradation after startup. Compose `depends_on` health conditions are startup ergonomics, **not** correctness controls; one-shot migration services complete before application readiness (§21.3).

**Runbooks.** §18.4 requires the following thirteen runbooks. **None of them are written yet** — the repository contains no runbook documents, so this list is outstanding work, not an inventory. A profile run may proceed without them, but §18.4 is not satisfied and the operational steps referenced elsewhere in this document (§13 step 5, §10 rotation and exposure) currently have no written procedure behind them:

1. growing indeterminate backlog;
2. outbox relay failure;
3. provider breaker activation;
4. float exhaustion;
5. reconciliation residual;
6. active-hold expiry failure;
7. balance invariant alert;
8. restore and projection rebuild;
9. signing-key rotation;
10. secret exposure;
11. memory/connection saturation and admission control;
12. disk high-water, WAL/archive blockage and outbox quota;
13. corrupted or incomplete backup rejection.

---

## 12) Acceptance criteria for this environment

The acceptance matrix is §23.1 of the parent. **It cannot all run under one profile**, and this document does not claim it does. ACC-25 is explicitly "run **each** declared profile under its exact versioned overlay and mixed-workload soak", and the supported PoC is an evidence suite executed **across** declared profiles (§21.1).

| Profile | Scenarios | Why this profile |
|---|---|---|
| `normal` | ACC-01, ACC-03–ACC-09, ACC-11–ACC-14, ACC-16, ACC-17, ACC-19–ACC-22, ACC-24, ACC-28, ACC-29, ACC-31, ACC-32, ACC-33, ACC-34, ACC-37–ACC-43 | single-replica functional, accounting, provider, reconciliation and product scenarios |
| `concurrency` | ACC-02, ACC-10, ACC-23, ACC-26, ACC-27, ACC-30, ACC-35, ACC-36 | ACC-02 requires **multiple `funds-core` replicas**; the rest need the concurrent-writer, saturation and watermark conditions that only this overlay budgets — two `funds-core` replicas (448 MiB × 2), the largest Go worker budget of the three profiles (1,024 MiB) and Grafana/Tempo plus nonessential import/export jobs stopped to pay for them (§4.1, §4.2, §21.1). Note this overlay does **not** give Redpanda extra headroom: the broker holds at 768 MiB and drops to 0.60 CPU, so broker-pressure scenarios are exercised under a *tighter* CPU budget, not a larger one |
| `restore` | ACC-15, ACC-18 | Temporal is stopped and PostgreSQL/replay workers get headroom; ACC-15 replays to a cutoff and ACC-18 restores from off-host backup |
| all three | ACC-25 | one soak, manifest and resource report per declared profile |

This assignment is itself a declared property of the run: a scenario that needs no extra replicas may be executed under a different profile provided the artifact records which profile and which configuration hash produced it (§21.1).

Each run must additionally satisfy (§21.16, §23.5): no monotonic post-warm-up memory growth, no unexpected OOM, no swap activity, no invariant failure, bounded catch-up after shedding, at least the declared 2,048 MiB reserve remaining, and a retained resource report that identifies which services were stopped and the exact overlay/configuration hash.

Environment-level criteria: the public API edge is reachable through the proxy over TLS; **no host port other than tcp/22 (or the configured SSH port), tcp/80 and tcp/443 is open**; internal services publish no host ports; all five datastores accept only authenticated encrypted connections. This is checked by the operator in §13 step 2 — the bootstrap script installs the firewall but does not itself assert the published-port surface.

---

## 13) Operational runbook (PoC host)

1. **Provision and start.** `sudo ./infrastructure/scripts/bootstrap-ubuntu24-poc.sh --profile normal`. Confirm the preflight summary, the manifest and that `up --wait` returned success. If any one-shot job uses the `profiles:` shape of §6, the script neither runs it nor detects that it is missing — and on a **first** provision there is no earlier moment to run it in, because this script is what installs Docker. Either wire the job as a `service_completed_successfully` dependency (§6), or bring the host up, run the job with `docker compose … run --rm <job>`, and re-run the script (§7 step 2).
2. **Verify the environment.** All containers healthy (`docker compose … ps -a`; the post-check already fails the run if a running container declares no healthcheck, is not `(healthy)`, crash-loops or exited non-zero); swap empty (`cat /proc/swaps`); only the configured SSH port, tcp/80 and tcp/443 reachable from outside. Check the published surface from the host with `ss -lntp` and, for the parts `ufw` cannot see, `docker compose … ps --format '{{.Name}} {{.Ports}}'` — the script installs the firewall but does not assert this itself.
3. **Run the profile's acceptance scenarios** (§12 above) and retain the artifacts together with the configuration hash.
4. **Under resource pressure**, switch **only to another declared profile**, and only via the scripted drain-and-record transition in §7. Never disable a component ad hoc to make a run fit: the concurrency profile's disabling of Grafana, Tempo and nonessential import/export jobs is a *declared property of that profile* (§21.1, §21.14), not a licence to remove components at runtime. If no declared profile fits, the remedy is to reduce scope or increase RAM/CPU — never to enable swap or conceal a component; concealing one fails ACC-25 (§21.1).
5. **On a container crash**, follow the applicable runbook from §11. Recovery relies on idempotent command replay and the durable outbox, not on restart policy alone (§19).
6. **Backups.** Verified base backup before each tagged demonstration; continuous off-host WAL archiving during the run; recovery-checkpoint manifest recorded. Restore drills run onto empty volumes: drain, then `docker volume rm` the stores under test by hand (§7), then start the `restore` profile. The scripted transition never removes volumes.
7. **After a host reboot**, re-run the bootstrap script: the `DOCKER-USER` firewall rules are not persisted at all — **but note this is a full re-provision, not a repair: it re-runs the unpinned apt install, drains and restarts the profile, and writes a new manifest (§5)**. **Swap masking, however, is already persistent without `--persist-swap-off`**: `systemctl mask` writes `/etc/systemd/system/<unit> → /dev/null`, which outranks the `.swap` units the fstab generator writes into `/run/systemd/generator`. `--persist-swap-off` adds one further thing — commenting the swap entries out of `/etc/fstab` (backed up first, matched on the fstype field only).
8. **To give this host its swap back** (it is no longer an evidence host after this):
   ```
   systemctl list-unit-files --state=masked | grep -E '\.swap|zram'   # what the script masked
   systemctl unmask <each unit listed>                                # e.g. swap.img.swap, zramswap.service
   sudo sed -i 's/^# disabled by core-banking bootstrap [^:]*: //' /etc/fstab   # only if --persist-swap-off was used
   swapon -a && cat /proc/swaps
   ```
   The timestamped `/etc/fstab.core-banking-<ts>.bak` written before any fstab edit is the authoritative copy to restore from.

---

## 14) Kubernetes migration guidance (future state)

The twelve-factor disciplines in §21.17 — external configuration, secrets consumed as mounted files or via a secrets API, no durable local application state, separate liveness and readiness endpoints, `SIGTERM` drain, stdout logging, one process per container, immutable images and explicit schema migrations — are what make a Kubernetes migration credible.

Skeletons already in this repository:

- `infrastructure/kubernetes/infra-modern-core-banking-poc/` — `namespace.yaml`, `deployments.yaml`, `services.yaml`, `ingress.yaml`, `postgres-statefulset.yaml`, `redpanda.yaml`, `redpanda-service.yaml`, `minio.yaml`, `minio-service.yaml`, `networkpolicies.yaml`, `secrets.yaml`.
- `infrastructure/helm/modern-core-banking-poc/` — a Helm chart (`Chart.yaml` version 0.2.0) with templates for the same objects **except the NetworkPolicy**, which exists only as the raw manifest above. A chart already exists; the open work is parameterising it for the `normal`/`concurrency`/`restore` profiles and adding the missing network policy, not deciding whether to have a chart.

§21.17 is explicit that production Kubernetes still requires **separate designs** for persistent storage, network policy, ingress, secrets, disruption budgets, autoscaling and operational ownership. Against that list the current skeletons are incomplete:

- **Network policy** — `networkpolicies.yaml` exists but covers only `api-edge → funds-core`. A default-deny ingress/egress baseline plus explicit allowances for every pair in §3 is required before it enforces §17.2.
- **Secrets** — `secrets.yaml` contains base64-encoded literal credentials committed to the repository. Base64 is not encryption; this violates §17.3 and §21.5 and must be replaced by SOPS-encrypted manifests or an external secrets provider before the manifests are applied anywhere shared.
- **Pod disruption budgets** — absent entirely.
- **Persistent storage** — the StatefulSet needs a StorageClass with the per-store quotas of §8 above; there are no PVCs for Temporal or Valkey yet.
- **Ingress** — must preserve "proxy is the sole public listener" (§17.2) and terminate TLS with mTLS to `api-edge`.

These manifests are not part of the single-VPS PoC evidence path. Nothing in §12 is satisfied by running them.

---

## 15) References

- Parent architecture: [Architecture entry point](../../ARCHITECTURE.md)
  - §6 system context; §7 service ownership and boundaries; §8.8 bitemporal fields; §8.14 account restrictions
  - §17.1–17.7 security, secrets, data protection, PoC identity minimum
  - §18.1–18.4 telemetry, business metrics, health endpoints, runbooks
  - §19 failure behaviour; §20 production deployment target
  - §21.1–21.17 single-VPS PoC deployment (profiles, compose topology, startup, storage, secrets, Temporal, Redpanda, memory controls, retention, observability, admission control, resource evidence, twelve-factor portability)
  - §22 provider simulator and fault injection; §23 verification and acceptance plan
- Version pins: `docs/superpowers/plans/2026-08-30-accounting-kernel-implementation.md`
- Host bootstrap: `infrastructure/scripts/bootstrap-ubuntu24-poc.sh`
- Kubernetes skeletons: `infrastructure/kubernetes/infra-modern-core-banking-poc/`, `infrastructure/helm/modern-core-banking-poc/`
