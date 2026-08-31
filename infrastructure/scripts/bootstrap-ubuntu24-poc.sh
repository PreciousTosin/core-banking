#!/usr/bin/env bash
#
# bootstrap-ubuntu24-poc.sh
#
# Provision one Ubuntu 24.04 LTS host for the Modern Core Banking single-VPS
# PoC and bring up one declared Compose profile (normal | concurrency |
# restore).
#
# Parent design: architecture/modern-core-banking-comprehensive-design-revised.md
#   §17.2 network boundaries      §21.1  declared profiles / ceilings
#   §17.3 secrets                 §21.2  compose topology
#   §18.3 health vs readiness     §21.3  drain-and-record profile transitions
#   §21.7 Redpanda (fs.aio-max-nr) §21.4 storage
#   §21.14 log rotation           §21.16 resource evidence
#
# Properties:
#   * idempotent: re-running converges the host and restarts the profile;
#   * swap removal is the LAST destructive step: it happens only after every
#     install, checkout and secret step has succeeded. Earlier steps do change
#     the host - the apt-daily units are masked, conflicting Docker packages are
#     removed and ufw is enabled - so this is "last", not "only";
#   * it never invents Compose files or credentials. When they are missing it
#     fails with an actionable message.
#
# It does NOT create compose files, secrets or Kubernetes objects.

set -Eeuo pipefail

# --- Globals -----------------------------------------------------------
SCRIPT_NAME=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
RUN_TS=$(date -u +%Y%m%dT%H%M%SZ)
readonly SCRIPT_NAME SCRIPT_DIR RUN_TS

readonly LOG_DIR=/var/log/core-banking
readonly EVIDENCE_DIR=/var/lib/core-banking/evidence
readonly COMPOSE_PROJECT=corebank
readonly SYSCTL_FILE=/etc/sysctl.d/99-core-banking-poc.conf
readonly DOCKER_DAEMON_JSON=/etc/docker/daemon.json
readonly DOCKER_KEYRING=/etc/apt/keyrings/docker.asc
readonly DOCKER_APT_LIST=/etc/apt/sources.list.d/docker.list
# Docker's published apt signing key (primary fingerprint of download.docker.com/linux/ubuntu/gpg).
readonly DOCKER_KEY_FPR=9DC858229FC7DD38854AE2D88D81803C0EBFCD88
readonly FW_CHAIN=CORE-BANKING

# Minimum host shape (§21.1: 4 vCPU / 8 GiB / NVMe, 2,048 MiB OS reserve).
# The RAM floor is the planned ceiling of the profile being started (§21.1 tables).
readonly MIN_CPUS=4
readonly MIN_MEM_KIB_NORMAL=7536640      # 7,360 MiB
readonly MIN_MEM_KIB_CONCURRENCY=7667712 # 7,488 MiB
readonly MIN_MEM_KIB_RESTORE=6291456     # 6,144 MiB
readonly MIN_DISK_KIB=41943040 # 40 GiB free for volumes, WAL and backups (local decision)

# Crash-loop detection needs two samples: a single read right after 'up --wait'
# can land in the brief 'running' window of a container that is looping.
readonly CRASHLOOP_SAMPLE_SECONDS=12

GIT_REPO=${GIT_REPO:-https://github.com/PreciousTosin/core-banking.git}
BRANCH=${BRANCH:-master}
PROFILE=${PROFILE:-normal}
REPO_DIR=${REPO_DIR:-}
SECRETS_DIR=${SECRETS_DIR:-/etc/core-banking/secrets}
WAIT_TIMEOUT=${WAIT_TIMEOUT:-300}
PERSIST_SWAP_OFF=0
SKIP_SECRETS=0
ALLOW_UNDERSIZED=0
ALLOW_MISSING_HEALTHCHECKS=0
VERBOSE=0
LOG_FILE=""
MANIFEST_FILE=""
COMPOSE_ARGS=()
COMPOSE_BASE=""
COMPOSE_OVERLAY=""
APT_OPTS=()

# --- Diagnostics -------------------------------------------------------
log()  { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
step() { printf '\n[%s] ==> %s\n' "$(date -u +%H:%M:%S)" "$*"; }
warn() { printf '[%s] WARNING: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die()  { printf '[%s] ERROR: %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; exit 1; }

on_err() {
  local rc=$1 line=$2 cmd=$3
  printf '\n[%s] FAILED: %s line %s exited %s\n  command: %s\n' \
    "$(date -u +%H:%M:%S)" "$SCRIPT_NAME" "$line" "$rc" "$cmd" >&2
  if [[ -n $LOG_FILE ]]; then printf '  log: %s\n' "$LOG_FILE" >&2; fi
  exit "$rc"
}
trap 'on_err "$?" "$LINENO" "$BASH_COMMAND"' ERR

usage() {
  cat <<EOF
Usage: sudo $SCRIPT_NAME [options]

Provisions an Ubuntu 24.04 host for the single-VPS PoC and starts one declared
Compose profile under the fixed project name "$COMPOSE_PROJECT".

Options:
  --profile <normal|concurrency|restore>  Declared profile to start (default: $PROFILE)
  --repo <url>                            Git remote to clone (default: $GIT_REPO)
  --branch <name>                         Branch to deploy (default: $BRANCH)
  --repo-dir <path>                       Checkout to deploy. Default: the checkout
                                          this script lives in, else /opt/core-banking
  --secrets-dir <path>                    Docker secret material (default: $SECRETS_DIR)
  --wait-timeout <seconds>                'compose up --wait' timeout, 1-999999 (default:
                                          $WAIT_TIMEOUT). 0 is rejected: Compose reads it as no timeout
  --persist-swap-off                      Also comment out swap entries in /etc/fstab
                                          (backed up first; fstype field only)
  --skip-secrets                          Development only: do not require secret material
  --allow-undersized                      Downgrade CPU/RAM/disk preflight to warnings.
                                          Runs started this way cannot produce ACC-25 evidence
  --allow-missing-healthchecks            Downgrade "every long-running service declares a
                                          healthcheck" to a warning (doc §6 contract)
  --verbose                               Trace execution (set -x)
  -h, --help                              Show this help

Every run writes a log to $LOG_DIR/bootstrap-<ts>.log and a host manifest to
$EVIDENCE_DIR/bootstrap-<ts>.json (§21.16).
EOF
}

# --- 1. parse_args -----------------------------------------------------
need_value() {
  # $1 = flag, $2 = number of args remaining including the flag
  [[ $2 -ge 2 ]] || { printf 'ERROR: %s requires a value\n\n' "$1" >&2; usage >&2; exit 2; }
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case $1 in
      --profile)        need_value "$1" "$#"; PROFILE=$2; shift 2 ;;
      --repo)           need_value "$1" "$#"; GIT_REPO=$2; shift 2 ;;
      --branch)         need_value "$1" "$#"; BRANCH=$2; shift 2 ;;
      --repo-dir)       need_value "$1" "$#"; REPO_DIR=$2; shift 2 ;;
      --secrets-dir)    need_value "$1" "$#"; SECRETS_DIR=$2; shift 2 ;;
      --wait-timeout)   need_value "$1" "$#"; WAIT_TIMEOUT=$2; shift 2 ;;
      --persist-swap-off) PERSIST_SWAP_OFF=1; shift ;;
      --skip-secrets)   SKIP_SECRETS=1; shift ;;
      --allow-undersized) ALLOW_UNDERSIZED=1; shift ;;
      --allow-missing-healthchecks) ALLOW_MISSING_HEALTHCHECKS=1; shift ;;
      --verbose)        VERBOSE=1; shift ;;
      -h|--help)        usage; exit 0 ;;
      --)               shift; break ;;
      *)                printf 'ERROR: unknown argument: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
  done

  case $PROFILE in
    normal|concurrency|restore) ;;
    *) die "invalid --profile '$PROFILE'; declared profiles are: normal, concurrency, restore" ;;
  esac
  # 0 is rejected on purpose: 'compose up --wait --wait-timeout 0' is treated as
  # "no timeout" and waits forever, which turns a stuck stack into a hung run
  # instead of a failed one (measured on Compose v5.5.0).
  [[ $WAIT_TIMEOUT =~ ^[1-9][0-9]{0,5}$ ]] ||
    die "--wait-timeout must be a positive integer number of seconds, 1-999999 ('0' means NO timeout to 'compose up --wait', which would hang this run instead of failing it)"
  if (( VERBOSE == 1 )); then set -x; fi
  return 0
}

# --- 2. require_root  /  logging ---------------------------------------
require_root() {
  [[ ${EUID:-$(id -u)} -eq 0 ]] || die "must run as root (apt, sysctl, swapoff and ufw all require it). Try: sudo $0 $*"
}

start_logging() {
  mkdir -p "$LOG_DIR"
  LOG_FILE="$LOG_DIR/bootstrap-$RUN_TS.log"
  exec > >(tee -a "$LOG_FILE") 2>&1
  log "$SCRIPT_NAME run $RUN_TS - profile=$PROFILE project=$COMPOSE_PROJECT log=$LOG_FILE"
}

# --- 3. preflight ------------------------------------------------------
preflight_fail() {
  if (( ALLOW_UNDERSIZED == 1 )); then
    warn "$1 (accepted because --allow-undersized was given; this host cannot produce ACC-25 evidence)"
  else
    die "$1 (pass --allow-undersized to continue anyway for non-evidence runs)"
  fi
}

cpu_model() {
  # /proc/cpuinfo's 'model name' is x86-only; on arm64 - accepted by preflight -
  # the file carries CPU implementer/part instead, so the §21.16 manifest would
  # record an empty cpu_model on Graviton/Ampere. lscpu (util-linux, always
  # present) names the core on both; the raw fields are the last resort.
  local m
  # '{ ...|| true; }': an absent or failing lscpu makes this ASSIGNMENT from a
  # pipeline non-zero under pipefail, which trips the ERR trap inside the
  # command substitution at the jq call - the fallbacks below never run and the
  # manifest records an empty cpu_model under a spurious FAILED line.
  m=$( { LC_ALL=C lscpu 2>/dev/null || true; } | awk -F': +' '/^Model name:/ {print $2; exit}')
  [[ -n $m ]] || m=$(awk -F': ' '/^model name/ {print $2; exit}' /proc/cpuinfo)
  [[ -n $m ]] || m=$(awk -F': +' '/^CPU implementer/ {i = $2} /^CPU part/ {p = $2}
    END {if (i != "") print "ARM implementer " i " part " p}' /proc/cpuinfo)
  printf '%s' "${m:-unknown}"
}

docker_root_dir() {
  # $1 = value to use when the daemon cannot be asked. Same constraint as
  # container_state_fields: 'docker info -f' writes a bare newline to STDOUT
  # before it fails, so an inline '|| printf unknown' fallback yields
  # "<newline>unknown" - an unusable path for df, and a manifest field that
  # starts with a newline.
  local d
  d=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null) || true
  printf '%s' "${d:-$1}"
}

storage_probe_path() {
  # The path whose filesystem actually holds Docker's images and volumes.
  # Before Docker is installed the best guess is /var/lib/docker when the
  # provider image already carries it (it is commonly a separate mount), else
  # /var/lib. After install_docker the real answer comes from the daemon
  # (recheck_storage_root below), because a pre-existing daemon.json may set
  # "data-root" and configure_docker_daemon deliberately preserves it.
  if [[ -d /var/lib/docker ]]; then printf '/var/lib/docker'; else printf '/var/lib'; fi
}

check_free_disk() {
  # $1 = path whose filesystem is measured
  local path=$1 disk_kib
  disk_kib=$(df -Pk "$path" 2>/dev/null | awk 'NR==2 {print $4}')
  [[ -n $disk_kib ]] || { warn "cannot measure free space on the filesystem holding $path"; return 0; }
  (( disk_kib >= MIN_DISK_KIB )) ||
    preflight_fail "found $((disk_kib / 1024 / 1024)) GiB free on the filesystem holding $path, need $((MIN_DISK_KIB / 1024 / 1024)) GiB for volumes, WAL and backups"
  log "free disk on the filesystem holding $path: $((disk_kib / 1024 / 1024)) GiB"
}

check_project_quota() {
  # $1 = path whose filesystem is inspected.
  # Docker 'local' volumes carry no byte quota unless the backing filesystem is
  # mounted with PROJECT quotas. §21.4 asks for per-store byte quotas; without
  # this the quota columns of the overlays are aspirational (doc §8). User
  # ('quota'/'usrquota') and group ('grpquota') quotas do NOT back 'size=' and
  # must not satisfy this gate.
  local path=$1 mnt fstype opts
  mnt=$(df -P "$path" 2>/dev/null | awk 'NR==2 {print $6}')
  fstype=$(df -PT "$path" 2>/dev/null | awk 'NR==2 {print $2}')
  opts=$(awk -v m="$mnt" '$2 == m {print $4}' /proc/mounts 2>/dev/null | tail -n1)
  case ",${opts}," in
    *,prjquota,*|*,pquota,*)
      log "project quotas present on $mnt ($fstype), which backs $path" ;;
    *)
      warn "$mnt ($fstype), which backs $path, is mounted without project quotas (options: ${opts:-unknown}); Docker 'local' volumes cannot enforce the per-store byte quotas of §21.4. Remount with prjquota (XFS/ext4) or back each store with a loopback image - see doc §8" ;;
  esac
}

recheck_storage_root() {
  # Called from main() after configure_docker_daemon: only then is Docker's real
  # data-root knowable. A provider host with a separate /var/lib/docker mount, or
  # a preserved "data-root" key, would otherwise have both storage gates
  # evaluated on the wrong filesystem and pass with no space and no quotas.
  step "Re-checking storage against Docker's data root"
  local root
  root=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || printf '')
  if [[ -z $root || ! -d $root ]]; then
    warn "could not read Docker's data root from 'docker info'; the storage checks stand on $(storage_probe_path)"
    return 0
  fi
  log "Docker data root: $root"
  check_free_disk "$root"
  check_project_quota "$root"
}

preflight() {
  step "Preflight"
  [[ -r /etc/os-release ]] || die "/etc/os-release is missing; this is not a supported Ubuntu host"

  local id version_id
  # shellcheck disable=SC1091
  id=$(. /etc/os-release && printf '%s' "${ID:-}")
  # shellcheck disable=SC1091
  version_id=$(. /etc/os-release && printf '%s' "${VERSION_ID:-}")
  [[ $id == ubuntu && $version_id == 24.04 ]] ||
    die "unsupported OS '$id $version_id'; the PoC host baseline is Ubuntu Server 24.04 LTS"

  local arch
  arch=$(dpkg --print-architecture)
  [[ $arch == amd64 || $arch == arm64 ]] || die "unsupported architecture '$arch'; expected amd64 or arm64"

  [[ -f /sys/fs/cgroup/cgroup.controllers ]] ||
    die "cgroup v2 unified hierarchy not mounted; per-container memory/CPU ceilings (§21.1) cannot be enforced"
  grep -qw memory /sys/fs/cgroup/cgroup.controllers ||
    die "cgroup v2 memory controller unavailable; memory ceilings cannot be enforced"

  local cpus mem_kib min_mem_kib storage_path
  cpus=$(nproc)
  mem_kib=$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)
  storage_path=$(storage_probe_path)
  case $PROFILE in
    concurrency) min_mem_kib=$MIN_MEM_KIB_CONCURRENCY ;;
    restore)     min_mem_kib=$MIN_MEM_KIB_RESTORE ;;
    *)           min_mem_kib=$MIN_MEM_KIB_NORMAL ;;
  esac

  (( cpus >= MIN_CPUS ))       || preflight_fail "found $cpus vCPU, the declared profiles need $MIN_CPUS"
  (( mem_kib >= min_mem_kib )) || preflight_fail "found $((mem_kib / 1024)) MiB RAM, the $PROFILE profile's planned ceiling is $((min_mem_kib / 1024)) MiB"
  check_free_disk "$storage_path"

  command -v systemctl >/dev/null || die "systemd is required (docker.service, swap units, timers)"
  check_project_quota "$storage_path"
  log "preflight ok: $id $version_id $arch, ${cpus} vCPU, $((mem_kib / 1024)) MiB RAM, storage checked on $storage_path"
}

# --- 4. apt environment ------------------------------------------------
configure_apt() {
  step "Configuring non-interactive apt"
  export DEBIAN_FRONTEND=noninteractive
  export NEEDRESTART_MODE=a
  export NEEDRESTART_SUSPEND=1
  APT_OPTS=(
    -y
    -o DPkg::Lock::Timeout=600
    -o Dpkg::Options::=--force-confold
    -o Dpkg::Options::=--force-confdef
  )
}

apt_get() { apt-get "${APT_OPTS[@]}" "$@"; }

apt_update_retry() {
  # DPkg::Lock::Timeout covers the dpkg FRONTEND lock (used by 'apt-get
  # install'), not the apt LISTS lock: with /var/lib/apt/lists/lock held,
  # 'apt-get update' fails immediately with rc=100 no matter how long the
  # timeout is. On a fresh cloud image cloud-init, apt-daily.service and
  # unattended-upgrades all hold it in the first minutes after boot, so retry.
  local attempt max=10 delay=15
  for (( attempt = 1; attempt <= max; attempt++ )); do
    if apt_get update; then
      return 0
    fi
    (( attempt < max )) || break
    warn "'apt-get update' failed (attempt $attempt/$max); another process is probably holding /var/lib/apt/lists/lock. Retrying in ${delay}s"
    sleep "$delay"
  done
  die "'apt-get update' failed $max times over $(( max * delay ))s. Something is holding the apt lists lock: check with 'fuser -v /var/lib/apt/lists/lock' and 'systemctl list-jobs', then re-run"
}

# --- 5. remove conflicting packages (Docker install docs) --------------
remove_conflicting_packages() {
  step "Removing packages that conflict with Docker Engine"
  local pkg removed=()
  for pkg in docker.io docker-doc docker-compose docker-compose-v2 docker-buildx podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q '^install ok installed$'; then
      removed+=("$pkg")
    fi
  done
  if (( ${#removed[@]} > 0 )); then
    log "removing: ${removed[*]}"
    apt_get remove "${removed[@]}"
  else
    log "none installed"
  fi
  if command -v snap >/dev/null && snap list docker >/dev/null 2>&1; then
    warn "the 'docker' snap is installed and will shadow Docker Engine; remove it with: snap remove docker"
  fi
}

# --- 6. base packages --------------------------------------------------
install_base_packages() {
  step "Installing base packages"
  apt_update_retry
  # sysstat backs the §21.16 resource evidence loop; jq builds the run manifest.
  apt_get install git curl ca-certificates gnupg jq sysstat ufw
}

# --- 7. Docker Engine --------------------------------------------------
write_file() {
  # write_file <path> <mode>; content on stdin. Returns 0 when the file changed,
  # 1 when it was already current, and dies when it could not be written.
  # Every caller tests the return value ('if', '|| true', '|| log'), and bash
  # disables errexit AND the ERR trap for the whole of a tested command - so an
  # unhandled 'install' failure would fall through to 'return 0' and be read as
  # "changed". Callers must NOT pipe into this function: 'die' would then exit
  # only the pipeline's subshell and the caller would read rc 1 as "unchanged".
  local path=$1 mode=$2 tmp
  tmp=$(mktemp)
  cat >"$tmp"
  if [[ -f $path ]] && cmp -s "$tmp" "$path"; then
    rm -f "$tmp"
    return 1
  fi
  install -D -m "$mode" "$tmp" "$path" || { rm -f "$tmp"; die "cannot write $path"; }
  rm -f "$tmp"
  return 0
}

install_docker() {
  step "Installing Docker Engine and the Compose plugin"
  local codename arch tmp
  # shellcheck disable=SC1091
  codename=$(. /etc/os-release && printf '%s' "${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}")
  [[ -n $codename ]] || die "cannot determine the Ubuntu codename from /etc/os-release"
  arch=$(dpkg --print-architecture)

  install -m 0755 -d /etc/apt/keyrings
  tmp=$(mktemp)
  curl -fsSL --retry 3 --retry-delay 2 https://download.docker.com/linux/ubuntu/gpg -o "$tmp"
  [[ -s $tmp ]] || { rm -f "$tmp"; die "downloaded Docker signing key is empty"; }
  # TLS alone authenticates the host, not the key. Pin Docker's published primary
  # fingerprint so a mirror, proxy or captive portal cannot substitute a key that
  # would then sign every package apt installs below. The pin is a substitution
  # tripwire, not out-of-band trust: Docker publishes no fingerprint outside the
  # key itself. GNUPGHOME is a throwaway directory - 'gpg --show-keys' otherwise
  # creates /root/.gnupg on the host.
  local fpr="" gnupghome
  gnupghome=$(mktemp -d)
  # '|| true': gpg exits 2 on non-key data, and under pipefail this ASSIGNMENT
  # from a pipeline trips the ERR trap before the die below can name the real
  # problem (a substituted key, or a captive-portal page served instead of it),
  # leaving $tmp and $gnupghome behind.
  fpr=$( { GNUPGHOME="$gnupghome" gpg --batch --no-options --show-keys --with-colons "$tmp" 2>/dev/null || true; } |
    awk -F: '$1 == "fpr" {print $10; exit}')
  rm -rf "$gnupghome"
  [[ $fpr == "$DOCKER_KEY_FPR" ]] || {
    rm -f "$tmp"
    die "the Docker apt signing key from download.docker.com has fingerprint '${fpr:-<unparseable>}', not the expected $DOCKER_KEY_FPR. Refusing to trust it: apt would accept anything it signs"
  }
  # Docker's current instructions ship the ASCII-armoured key directly; no
  # gpg --dearmor step, which was the non-idempotent part of the old script.
  install -m 0644 "$tmp" "$DOCKER_KEYRING"
  rm -f "$tmp"
  # Retire the dearmoured keyring written by earlier revisions of this script,
  # and the deb822 source Docker's own install page writes: keeping both it and
  # $DOCKER_APT_LIST produces duplicate-source warnings on every apt-get update.
  rm -f /etc/apt/keyrings/docker.gpg
  rm -f /etc/apt/sources.list.d/docker.sources

  # Here-string, not a pipe: write_file dies on a failed install, and from a
  # pipeline that die would exit only the subshell (see write_file).
  local apt_line
  printf -v apt_line 'deb [arch=%s signed-by=%s] https://download.docker.com/linux/ubuntu %s stable' \
    "$arch" "$DOCKER_KEYRING" "$codename"
  write_file "$DOCKER_APT_LIST" 0644 <<<"$apt_line" || true

  apt_update_retry
  apt_get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
  docker compose version >/dev/null || die "docker compose plugin is not usable after install"
  log "$(docker --version); $(docker compose version)"
}

# --- 8. Docker daemon configuration (§21.14 log rotation is parent-mandated) 
configure_docker_daemon() {
  step "Configuring $DOCKER_DAEMON_JSON"
  # Merge, never overwrite: a host may already carry data-root, registry
  # mirrors or an ip6tables setting that this script has no business dropping.
  # The keys below win; everything else in the file survives.
  local desired current merged
  desired=$(cat <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "32m",
    "max-file": "5"
  },
  "live-restore": true,
  "default-ulimits": {
    "nofile": { "Name": "nofile", "Hard": 65536, "Soft": 65536 }
  }
}
JSON
)
  current='{}'
  if [[ -f $DOCKER_DAEMON_JSON ]]; then
    current=$(cat "$DOCKER_DAEMON_JSON")
    [[ -n ${current//[[:space:]]/} ]] || current='{}'
    jq -e . >/dev/null 2>&1 <<<"$current" ||
      die "$DOCKER_DAEMON_JSON is not valid JSON; fix it or move it aside and re-run (this script merges into it, it does not replace it)"
  fi
  merged=$(jq -S --argjson desired "$desired" '. * $desired' <<<"$current")
  # Here-string, not a pipe (see write_file): '<<<' appends the same trailing
  # newline printf '%s\n' did, so the idempotence compare is unchanged.
  if write_file "$DOCKER_DAEMON_JSON" 0644 <<<"$merged"
  then
    log "daemon.json changed; restarting docker"
    systemctl restart docker
  else
    log "daemon.json already current"
  fi
}

# --- 9. Kernel tunables ------------------------------------------------
configure_sysctl() {
  step "Applying kernel tunables ($SYSCTL_FILE)"
  # fs.aio-max-nr: Redpanda/Seastar needs 1048576; the Ubuntu default of 65536
  # makes the constrained broker fail §21.7 / ACC-35.
  # The rest raise limits the container fleet would otherwise exhaust.
  # vm.swappiness is deliberately absent: swap is disabled outright below.
  write_file "$SYSCTL_FILE" 0644 <<'CONF' || log "sysctl file already current"
# Managed by bootstrap-ubuntu24-poc.sh - Modern Core Banking single-VPS PoC
fs.aio-max-nr = 1048576
fs.file-max = 2097152
net.core.somaxconn = 4096
fs.inotify.max_user_instances = 1024
fs.inotify.max_user_watches = 524288
CONF
  sysctl -q -p "$SYSCTL_FILE"
  log "fs.aio-max-nr=$(sysctl -n fs.aio-max-nr)"
}

# --- 10. Firewall (§17.2: the reverse proxy is the only public listener) 
sshd_listen_ports() {
  # Echo every port SSH may be reachable on, one per line - the UNION of two
  # oracles, never one of them:
  #   * ssh.socket's ListenStream entries. openssh-server on 24.04 is
  #     socket-activated. Its sshd-socket-generator normally propagates
  #     'Port'/'ListenAddress' from sshd_config into ssh.socket.d, so the two
  #     usually agree - but an operator who overrides ssh.socket directly
  #     (systemctl edit ssh.socket) moves the real listener while 'sshd -T'
  #     still reports 22, and there is a window before 'daemon-reload'.
  #   * 'sshd -T' (exits non-zero when /run/sshd is missing, hence the guard).
  # A superset only ever opens an extra port; a subset locks the operator out.
  # 22 remains the fallback when neither oracle yields anything (doc §5).
  local ports="" socket_ports="" config_ports=""
  if command -v systemctl >/dev/null 2>&1; then
    # 'systemctl show' on a non-existent unit prints nothing and exits 0. Each
    # listener is one 'addr:port (Stream)' line; strip the protocol suffix and
    # skip entries with no ':' (unix/FIFO listeners).
    socket_ports=$( (systemctl show ssh.socket -p Listen --value 2>/dev/null || true) |
      sed 's/ (.*)$//' | awk -F: 'NF > 1 {print $NF}')
  fi
  if command -v sshd >/dev/null 2>&1; then
    config_ports=$( (sshd -T 2>/dev/null || true) | awk '/^port /{print $2}')
  fi
  ports=$(printf '%s\n%s\n' "$socket_ports" "$config_ports" | awk 'NF' | sort -u)
  [[ -n $ports ]] || ports=22
  printf '%s\n' "$ports"
}

apply_docker_user_chain() {
  # $1 = iptables binary (iptables|ip6tables), $2 = public interface.
  local ipt=$1 pub_if=$2
  "$ipt" -N DOCKER-USER 2>/dev/null || true
  "$ipt" -N "$FW_CHAIN" 2>/dev/null || true
  # Flush-and-refill leaves the chain empty for the few milliseconds it takes to
  # re-append the four rules below, during which DOCKER-USER returns and
  # published ports are unfiltered. Accepted: a build-new-chain-and-swap dance
  # needs a second chain plus a jump rewrite for a sub-second window on a host
  # that is being re-provisioned anyway. Re-run only when that is acceptable.
  "$ipt" -F "$FW_CHAIN"
  "$ipt" -A "$FW_CHAIN" -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN
  "$ipt" -A "$FW_CHAIN" ! -i "$pub_if" -j RETURN
  "$ipt" -A "$FW_CHAIN" -p tcp -m multiport --dports 80,443 -j RETURN
  "$ipt" -A "$FW_CHAIN" -j DROP
  "$ipt" -C DOCKER-USER -j "$FW_CHAIN" 2>/dev/null || "$ipt" -I DOCKER-USER 1 -j "$FW_CHAIN"
  log "$ipt: DOCKER-USER drops new inbound traffic on $pub_if except tcp/80 and tcp/443"
}

assert_docker_user_hooked() {
  # $1 = iptables binary, $2 = severity handler (die|warn).
  # 'apply_docker_user_chain' creates DOCKER-USER itself when it is absent, so
  # the chain always ends up populated - but it only filters anything if dockerd
  # routes FORWARD through it. Docker installs that jump only when it manages
  # the ruleset, so an orphan chain is the silent failure mode this catches.
  local ipt=$1 severity=$2
  if "$ipt" -C FORWARD -j DOCKER-USER 2>/dev/null; then
    log "$ipt: FORWARD jumps to DOCKER-USER (the $FW_CHAIN rules are on the packet path)"
    return 0
  fi
  "$severity" "$ipt: FORWARD does not jump to DOCKER-USER, so the $FW_CHAIN rules filter nothing and published container ports are unprotected on this address family. Usual causes: \"iptables\": false (or \"ip6tables\": false) in $DOCKER_DAEMON_JSON - this script merges into that file and deliberately preserves such a key - or a dockerd using the nftables firewall backend (check: docker info --format '{{.FirewallBackend}}'). Remove the setting or switch the backend to iptables, restart docker, and re-run"
}

configure_firewall() {
  step "Configuring ufw and the DOCKER-USER chain"
  ufw --force default deny incoming
  ufw --force default allow outgoing
  local ssh_ports=() allowed=() port
  mapfile -t ssh_ports < <(sshd_listen_ports)
  for port in "${ssh_ports[@]}"; do
    # Range-checked, not merely numeric: an exotic ssh.socket listener can leave
    # a non-port token (a unix path containing ':') or an out-of-range vsock port
    # in the list, and 'ufw allow 0/tcp' aborts the run one line before enable.
    if ! [[ $port =~ ^[1-9][0-9]{0,4}$ ]] || (( port > 65535 )); then
      warn "ignoring '$port' from the ssh listener probe: not a TCP port number"
      continue
    fi
    ufw allow "$port/tcp"
    allowed+=("$port")
  done
  # The fallback in sshd_listen_ports only fires when BOTH oracles are silent. If
  # they spoke but every token was unusable, no SSH rule exists at all and the
  # 'ufw --force enable' below would lock out new connections.
  if (( ${#allowed[@]} == 0 )); then
    warn "the ssh listener probe yielded no usable port (${ssh_ports[*]:-none}); falling back to tcp/22 so 'ufw --force enable' cannot lock this host out"
    ufw allow 22/tcp
    allowed=(22)
  fi
  log "ufw allows sshd port(s): ${allowed[*]}"
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw --force enable
  ufw status verbose || true

  # Docker publishes ports straight into the nat/filter FORWARD path, so ufw
  # rules do not apply to them. DOCKER-USER is the documented hook.
  if ! command -v iptables >/dev/null; then
    warn "iptables not found; published container ports are NOT filtered"
    return 0
  fi
  # 'ip route show default' has several shapes ("default dev eth0 scope link",
  # multipath "nexthop via ... dev ...", "nhid N via ..."): a positional field
  # yields a non-interface, and '! -i <garbage>' then RETURNs every packet, so
  # the chain would report success while filtering nothing.
  local pub_if
  pub_if=$(ip -o route show default 2>/dev/null |
    awk '{for (i = 1; i <= NF; i++) if ($i == "dev") {print $(i + 1); exit}}')
  [[ -n $pub_if ]] || { warn "no default route with a 'dev' field; skipping DOCKER-USER rules"; return 0; }
  [[ -e "/sys/class/net/$pub_if" ]] ||
    die "parsed '$pub_if' as the public interface from the default route, but it is not in /sys/class/net; refusing to install a DOCKER-USER chain that would filter nothing"

  apply_docker_user_chain iptables "$pub_if"
  assert_docker_user_hooked iptables die
  # Docker 28+ enables ip6tables by default and keeps a separate v6 chain set.
  # A compose network with enable_ipv6 would otherwise publish every port to the
  # world over IPv6 while the v4 chain reports success (the PoC networks are
  # IPv4-only, so this is defence in depth - warn, never die).
  if command -v ip6tables >/dev/null && ip6tables -S DOCKER-USER >/dev/null 2>&1; then
    apply_docker_user_chain ip6tables "$pub_if"
    assert_docker_user_hooked ip6tables warn
  else
    warn "ip6tables or its DOCKER-USER chain is unavailable; published ports are filtered on IPv4 only. Keep every Compose network IPv4-only (no enable_ipv6) - see doc §9"
  fi
  warn "iptables rules are not persisted across reboot; re-run this script after a reboot"
}

# --- 11. Freeze background package activity during evidence runs -------
# Called from main() immediately after configure_apt and BEFORE any apt
# operation: the units below are what hold /var/lib/apt/lists/lock and the dpkg
# frontend lock on a freshly booted cloud image, so masking them afterwards
# (as earlier revisions did) left every apt call in this script exposed.
disable_unattended_upgrades() {
  step "Freezing background package activity (apt timers, unattended upgrades, cloud-init)"
  local unit
  # The .service units matter as much as their timers: masking only the timers
  # stops future triggers but leaves a job that is already running in place.
  for unit in apt-daily.timer apt-daily-upgrade.timer \
              apt-daily.service apt-daily-upgrade.service \
              unattended-upgrades.service; do
    systemctl disable --now "$unit" >/dev/null 2>&1 || true
    systemctl mask "$unit" >/dev/null 2>&1 || true
  done

  # cloud-init's package-update-upgrade-install module takes the same locks.
  # 'cloud-init status --wait' waits forever when the status is 'not started',
  # so it is guarded by 'command -v', bounded by 'timeout', and never fatal.
  if command -v cloud-init >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
    log "waiting up to 300s for cloud-init to settle"
    timeout 300 cloud-init status --wait >/dev/null 2>&1 ||
      warn "cloud-init did not report 'done' within 300s; continuing (apt calls are retried)"
  fi

  # Stopping unattended-upgrades mid-run can leave packages half configured,
  # which makes the first 'apt-get install' fail with a dpkg error.
  dpkg --configure -a >/dev/null 2>&1 ||
    warn "'dpkg --configure -a' reported an error; continuing, but expect apt failures if packages are half configured"
  write_file /etc/apt/apt.conf.d/99-core-banking-poc 0644 <<'CONF' || true
// Managed by bootstrap-ubuntu24-poc.sh - an evidence run must not be
// interrupted by a dockerd restart or a reboot request.
APT::Periodic::Enable "0";
APT::Periodic::Update-Package-Lists "0";
APT::Periodic::Unattended-Upgrade "0";
Unattended-Upgrade::Automatic-Reboot "false";
CONF
  log "apt-daily timers and services masked"
}

# --- 12. Time synchronisation (bitemporal records, §8.8) ---------------
# Called from main() immediately after preflight, before any TLS fetch.
assert_time_sync() {
  step "Asserting host clock synchronisation"
  systemctl enable --now systemd-timesyncd >/dev/null 2>&1 || true
  local i synced=no
  for i in $(seq 1 12); do
    synced=$(timedatectl show -p NTPSynchronized --value 2>/dev/null || printf 'no')
    [[ $synced == yes ]] && break
    log "clock not yet synchronised (attempt $i/12); waiting 5s"
    sleep 5
  done
  [[ $synced == yes ]] ||
    die "host clock is not NTP-synchronised; bitemporal records (§8.8) and evidence timestamps would be untrustworthy. Fix time sync (systemd-timesyncd or chrony) and re-run"
  log "clock synchronised: $(timedatectl show -p TimeUSec --value 2>/dev/null || date -u)"
}

# --- 13. Repository ----------------------------------------------------
mark_safe_directory() {
  # root operating on a checkout owned by another user.
  git config --global --get-all safe.directory 2>/dev/null | grep -qxF "$1" ||
    git config --global --add safe.directory "$1" 2>/dev/null || true
}

detect_in_checkout() {
  # Echo the checkout root when this script is running from inside one.
  local top
  # safe.directory is bypassed for this probe only: root reading a checkout owned
  # by the login user is the normal 'sudo ./infrastructure/scripts/...' case, and
  # git's dubious-ownership refusal (rc 128) is indistinguishable from "not a
  # checkout" here - it would silently divert the run to a managed clone of
  # origin/$BRANCH. mark_safe_directory below registers the exception properly.
  top=$(git -c safe.directory='*' -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null) || return 1
  [[ -f "$top/infrastructure/scripts/$SCRIPT_NAME" ]] || return 1
  printf '%s' "$top"
}

resolve_repo() {
  step "Resolving the checkout to deploy"

  # An existing checkout is deployed exactly as it stands: the operator asked
  # for that tree, and silently resetting it to origin/$BRANCH would deploy
  # something other than what they are looking at (NEW-4).
  # -e, not -d: in a linked worktree or a submodule .git is a 'gitdir:' FILE.
  if [[ -n $REPO_DIR && -e "$REPO_DIR/.git" ]]; then
    REPO_DIR=$(cd -- "$REPO_DIR" && pwd)
    mark_safe_directory "$REPO_DIR"
    log "deploying the existing checkout at $REPO_DIR in place (no clone, no fetch)"
    log "commit $(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || printf unknown)"
    return 0
  fi
  if [[ -z $REPO_DIR ]] && REPO_DIR=$(detect_in_checkout); then
    mark_safe_directory "$REPO_DIR"
    log "running from inside a checkout; deploying it in place: $REPO_DIR"
    log "local edits in this tree are what gets deployed; commit $(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || printf unknown)"
    return 0
  fi

  # Managed clone: either an explicit empty --repo-dir or the default location.
  [[ -n $REPO_DIR ]] || REPO_DIR=/opt/core-banking
  mkdir -p "$REPO_DIR"
  REPO_DIR=$(cd -- "$REPO_DIR" && pwd)
  mark_safe_directory "$REPO_DIR"
  log "cloning $GIT_REPO ($BRANCH) into $REPO_DIR"
  if [[ -e "$REPO_DIR/.git" ]]; then
    git -C "$REPO_DIR" remote set-url origin "$GIT_REPO"
    git -C "$REPO_DIR" fetch --prune origin "$BRANCH"
    git -C "$REPO_DIR" checkout -B "$BRANCH" "origin/$BRANCH"
  else
    rmdir "$REPO_DIR" 2>/dev/null || true
    # The rmdir above only removes it when it is empty. Naming the path here
    # matters most when REPO_DIR fell through to the default: the operator never
    # typed /opt/core-banking and git's own refusal would be the first mention.
    if [[ -d $REPO_DIR ]]; then
      die "$REPO_DIR exists, is not empty and is not a git checkout, so it cannot be used as a managed clone target. Either empty it, point --repo-dir at an existing checkout, or choose another --repo-dir"
    fi
    git clone --branch "$BRANCH" --single-branch "$GIT_REPO" "$REPO_DIR"
  fi
  log "checkout at $(git -C "$REPO_DIR" rev-parse --short HEAD) on $BRANCH"
}

resolve_compose_files() {
  step "Resolving Compose files for profile '$PROFILE'"
  local base="$REPO_DIR/infrastructure/compose/docker-compose.yml"
  local overlay="$REPO_DIR/infrastructure/compose/docker-compose.$PROFILE.yml"
  local missing=()
  [[ -f $base ]]    || missing+=("$base")
  [[ -f $overlay ]] || missing+=("$overlay")
  if (( ${#missing[@]} > 0 )); then
    die "missing Compose file(s):
$(printf '  %s\n' "${missing[@]}")
The PoC stack is a base file plus one overlay per declared profile (§21.1, §21.2).
This script never substitutes a different topology: create the files above (each
listing every container with its image digest, CPU quota, memory limit/reservation,
PIDs limit and volume quota) and re-run."
  fi
  COMPOSE_ARGS=(--project-name "$COMPOSE_PROJECT" -f "$base" -f "$overlay")
  # Kept separately: the manifest gate runs Compose from the compose directory
  # (see write_evidence_manifest) and needs the two paths, not the flag list.
  COMPOSE_BASE=$base
  COMPOSE_OVERLAY=$overlay
  log "compose: base=$base overlay=$overlay project=$COMPOSE_PROJECT"
}

# --- 14. Secrets (§17.3, §21.5: SOPS-encrypted config plus Docker secrets) 
bootstrap_secrets() {
  step "Bootstrapping Docker secret material in $SECRETS_DIR"
  install -d -m 0700 "$SECRETS_DIR"
  # Canonicalise like REPO_DIR: a relative --secrets-dir would create the
  # directory under the invoking cwd but be exported to Compose as a relative
  # path, where 'secrets: file:' resolves it against the COMPOSE FILE's
  # directory instead - i.e. a different directory than the one just populated.
  SECRETS_DIR=$(cd -- "$SECRETS_DIR" && pwd)
  # The Compose base file must back its secrets: entries with this path, e.g.
  #   file: ${CB_SECRETS_DIR:-/etc/core-banking/secrets}/postgres_password
  # Without the export, --secrets-dir would populate a directory the stack
  # never reads (doc §10).
  export CB_SECRETS_DIR="$SECRETS_DIR"

  if (( SKIP_SECRETS == 1 )); then
    warn "--skip-secrets: not verifying secret material. Development only; an evidence run requires real secrets"
    return 0
  fi

  local src="$REPO_DIR/infrastructure/secrets"
  local decrypted=0 f out tmp
  if [[ -d $src ]] && command -v sops >/dev/null; then
    shopt -s nullglob
    # Convention (doc §10): one encrypted file per secret value, named
    # <secret>.enc; the decrypted file is <secret>. A whole encrypted YAML
    # document is not a Docker secret, so *.enc.yaml/*.enc.yml are not decrypted.
    for f in "$src"/*.enc.yaml "$src"/*.enc.yml; do
      warn "skipping $(basename -- "$f"): a multi-value encrypted document is not a Docker secret file. Split it into one <secret>.enc file per value (doc §10)"
    done
    for f in "$src"/*.enc; do
      out="$SECRETS_DIR/$(basename -- "${f%.enc}")"
      log "decrypting $(basename -- "$f") -> $(basename -- "$out")"
      # Decrypt to a temp file: redirecting straight onto $out truncates it
      # first, so a failed decrypt (missing age/KMS key) would leave an empty
      # file that a later run mistakes for real secret material.
      tmp=$(mktemp)
      if ( umask 077; sops --decrypt "$f" >"$tmp" ) && [[ -s $tmp ]]; then
        install -m 0600 "$tmp" "$out"
        rm -f "$tmp"
      else
        rm -f "$tmp"
        die "sops --decrypt failed (or produced an empty file) for $f; $out was left untouched. Check SOPS_AGE_KEY_FILE / the KMS credentials and re-run"
      fi
      decrypted=$((decrypted + 1))
    done
    shopt -u nullglob
  elif [[ -d $src ]]; then
    warn "$src exists but 'sops' is not installed; install sops or place decrypted files in $SECRETS_DIR yourself"
  fi

  chmod 0700 "$SECRETS_DIR"
  find "$SECRETS_DIR" -type f -exec chmod 0600 {} +

  local count
  # ! -empty: a zero-byte leftover from an earlier failed decrypt must not
  # satisfy the gate and start the stack with blank credentials.
  count=$(find "$SECRETS_DIR" -type f ! -empty | wc -l)
  if (( count == 0 )); then
    die "no non-empty secret material in $SECRETS_DIR (decrypted $decrypted file(s)).
§17.3 forbids a 'no secrets manager' mode. Either:
  * commit one SOPS-encrypted file per secret value under infrastructure/secrets/,
    named <secret>.enc, and install sops; or
  * generate the files the overlays reference, e.g.
      install -d -m 0700 $SECRETS_DIR
      ( umask 077; openssl rand -base64 32 > $SECRETS_DIR/postgres_password )
Then re-run. Use --skip-secrets only for throwaway development runs."
  fi
  log "$count non-empty secret file(s) present ($decrypted decrypted this run); CB_SECRETS_DIR=$CB_SECRETS_DIR"
}

# --- 15. Run manifest (§21.16, ACC-25 overlay/configuration hash) ------
write_evidence_manifest() {
  step "Writing the run manifest"
  install -d -m 0755 "$EVIDENCE_DIR"
  MANIFEST_FILE="$EVIDENCE_DIR/bootstrap-$RUN_TS.json"
  local manifest="$MANIFEST_FILE"

  local config_hash images commit swap_state timesync
  local config_file err_file images_file images_raw tagged compose_dir base_name overlay_name
  config_file=$(mktemp)
  err_file=$(mktemp)
  images_file=$(mktemp)
  # Both gates below run Compose from the compose directory with RELATIVE -f
  # paths. Under '--no-path-resolution' (the hash gate) Compose stops resolving
  # 'env_file:' against the project directory and reads it relative to the
  # invoking process's cwd, so from any other cwd (typically the repo root) a
  # perfectly startable stack fails here with the misleading "the profile
  # overlay is not valid". '--project-directory' does NOT fix it; only the cd
  # does - measured on Compose v5.5.0 (docker-compose-plugin, Ubuntu 24.04).
  # Plain 'config' (the digest gate) is cwd-independent; it shares the cd only
  # so both gates see one identical invocation shape.
  compose_dir=$(dirname -- "$COMPOSE_BASE")
  base_name=$(basename -- "$COMPOSE_BASE")
  overlay_name=$(basename -- "$COMPOSE_OVERLAY")
  # --no-path-resolution keeps the hash independent of $REPO_DIR: the same
  # overlay must produce the same ACC-25 identity from any checkout path. It is
  # used for the hash ONLY - never on the real 'up', where bind mounts must
  # still resolve. Note that env-file VALUES are inlined into this output, so
  # the identity hash moves when an env file's contents change (doc §6).
  if ! ( cd -- "$compose_dir" &&
         docker compose --project-name "$COMPOSE_PROJECT" \
           -f "$base_name" -f "$overlay_name" config --no-path-resolution
       ) >"$config_file" 2>"$err_file"; then
    local config_err
    config_err=$(cat "$err_file")
    rm -f "$config_file" "$err_file" "$images_file"
    die "'docker compose config' failed; the profile overlay is not valid:
$config_err"
  fi
  config_hash=$(sha256sum <"$config_file" | awk '{print $1}')
  rm -f "$config_file"

  # §21.16 requires image digests in the report. 'config --images' emits
  # whatever the overlay wrote, and --resolve-image-digests is a no-op when
  # combined with --images (and contacts the registry on its own), so gate here.
  # --profile '*' is what makes the gate complete: services carrying a compose
  # 'profiles:' key (the sanctioned shape for the Flyway one-shots, doc §6) are
  # otherwise invisible to 'config', and an unpinned image would slip through.
  # It applies to this gate ONLY - the identity hash above stays the as-invoked
  # set, so previously recorded ACC-25 hashes remain comparable.
  if ! ( cd -- "$compose_dir" &&
         docker compose --project-name "$COMPOSE_PROJECT" --profile '*' \
           -f "$base_name" -f "$overlay_name" config --images
       ) >"$images_file" 2>"$err_file"; then
    local images_err
    images_err=$(cat "$err_file")
    rm -f "$images_file" "$err_file"
    die "'docker compose config --images' failed, so the §21.16 image list and the
digest-pinning gate cannot be evaluated:
$images_err"
  fi
  images_raw=$(sed '/^[[:space:]]*$/d' "$images_file" | sort -u)
  rm -f "$images_file" "$err_file"
  # An empty list means the gate below would pass vacuously and the manifest
  # would record "images": [] as ACC-25 evidence.
  [[ -n $images_raw ]] || die "'docker compose config --images' listed no images for profile '$PROFILE'.
The base file and the overlay must declare every active container with a
digest-pinned image (§21.1, §21.16); a run with no images cannot be evidence."
  tagged=$(printf '%s\n' "$images_raw" | grep -v '@sha256:' || true)
  [[ -z $tagged ]] || die "these images are not pinned by digest:
$(printf '%s\n' "$tagged" | sed 's/^/  /')
§21.1/§21.16 require every image in the base file and the overlay to be pinned
as name@sha256:...; a tag cannot identify what was run. Fix the Compose files
and re-run."
  images=$(printf '%s\n' "$images_raw" | jq -R . | jq -s .)
  commit=$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null || printf 'unknown')
  local os_name
  # shellcheck disable=SC1091
  os_name=$(. /etc/os-release && printf '%s %s' "${NAME:-}" "${VERSION:-}")
  swap_state=$(awk 'NR>1 {print $1}' /proc/swaps | paste -sd, - )
  timesync=$(timedatectl show -p NTPSynchronized --value 2>/dev/null || printf 'unknown')

  jq -n \
    --arg run_ts "$RUN_TS" \
    --arg profile "$PROFILE" \
    --arg project "$COMPOSE_PROJECT" \
    --arg repo_dir "$REPO_DIR" \
    --arg branch "$BRANCH" \
    --arg commit "$commit" \
    --arg compose_files "${COMPOSE_ARGS[*]}" \
    --arg config_sha256 "$config_hash" \
    --argjson images "$images" \
    --arg kernel "$(uname -srmo)" \
    --arg os "$os_name" \
    --arg cpu_model "$(cpu_model)" \
    --argjson nproc "$(nproc)" \
    --argjson mem_total_kib "$(awk '/^MemTotal:/ {print $2}' /proc/meminfo)" \
    --arg docker_root_dir "$(docker_root_dir unknown)" \
    --arg disk_avail "$(df -Ph "$(docker_root_dir "$(storage_probe_path)")" | awk 'NR==2 {print $4}')" \
    --arg cgroup "$([[ -f /sys/fs/cgroup/cgroup.controllers ]] && printf v2 || printf v1)" \
    --arg docker "$(docker --version)" \
    --arg compose "$(docker compose version --short 2>/dev/null || printf unknown)" \
    --arg swap_devices "${swap_state:-none}" \
    --arg ntp_synchronised "$timesync" \
    --argjson wait_timeout "$WAIT_TIMEOUT" \
    --argjson allow_undersized "$ALLOW_UNDERSIZED" \
    --argjson allow_missing_healthchecks "$ALLOW_MISSING_HEALTHCHECKS" \
    --argjson skip_secrets "$SKIP_SECRETS" \
    --argjson persist_swap_off "$PERSIST_SWAP_OFF" \
    '$ARGS.named' >"$manifest"

  chmod 0644 "$manifest"
  cat "$manifest"
  log "manifest: $manifest"
  log "swap_devices above is the pre-swapoff state; it is rewritten after the swap step"
  log "record this configuration hash with every ACC-25 artifact: $config_hash"
}

refresh_manifest_swap_state() {
  # The manifest is written before the swap step (it doubles as the early
  # 'docker compose config' gate, ahead of anything destructive), so the
  # recorded swap_devices would otherwise be the pre-swapoff state - exactly
  # the field §21.16 uses to show the run had no swap.
  [[ -n $MANIFEST_FILE && -f $MANIFEST_FILE ]] || return 0
  local swap_state tmp
  swap_state=$(awk 'NR>1 {print $1}' /proc/swaps | paste -sd, -)
  tmp=$(mktemp)
  jq --arg swap_devices "${swap_state:-none}" '.swap_devices = $swap_devices' \
    "$MANIFEST_FILE" >"$tmp"
  install -m 0644 "$tmp" "$MANIFEST_FILE"
  rm -f "$tmp"
  log "manifest swap_devices set to '${swap_state:-none}' (state after the swap step)"
}

# --- 16. Swap (last destructive step; §21.1 evidence validity) ---------
disable_swap() {
  step "Disabling swap"
  swapoff -a
  local unit
  local zram_units=()
  while read -r unit; do
    [[ -n $unit ]] || continue
    log "masking swap unit $unit"
    systemctl mask "$unit" >/dev/null 2>&1 || warn "could not mask $unit"
  done < <(systemctl list-units --type=swap --all --no-legend --plain 2>/dev/null |
    awk '{for (i = 1; i <= NF; i++) if ($i ~ /\.swap$/) {print $i; next}}')

  # Ubuntu has two zram providers: systemd-zram-generator instantiates
  # systemd-zram-setup@<dev>.service (one per configured device, not just
  # zram0), while the zram-tools package ships zramswap.service.
  if systemctl list-unit-files 'zramswap.service' --no-legend 2>/dev/null | grep -q zramswap; then
    systemctl disable --now zramswap.service >/dev/null 2>&1 || true
    systemctl mask zramswap.service >/dev/null 2>&1 || true
    log "masked zramswap.service (zram-tools)"
  fi
  if systemctl list-unit-files 'systemd-zram-setup@*' --no-legend 2>/dev/null | grep -q zram; then
    mapfile -t zram_units < <(systemctl list-units 'systemd-zram-setup@*' --all --no-legend --plain 2>/dev/null |
      awk '{for (i = 1; i <= NF; i++) if ($i ~ /^systemd-zram-setup@.*\.service$/) {print $i; next}}')
    (( ${#zram_units[@]} > 0 )) || zram_units=('systemd-zram-setup@zram0.service')
    for unit in "${zram_units[@]}"; do
      systemctl stop "$unit" >/dev/null 2>&1 || true
      systemctl mask "$unit" >/dev/null 2>&1 || true
      log "masked $unit (systemd-zram-generator)"
    done
  fi

  local active
  active=$(awk 'NR>1' /proc/swaps | wc -l)
  (( active == 0 )) || die "swap is still active after swapoff -a:
$(cat /proc/swaps)
An evidence-producing run is invalid while swap is available (§21.1)."

  if (( PERSIST_SWAP_OFF == 1 )); then
    persist_swap_off
  else
    log "swap is off, and the masks above persist across reboot ('systemctl mask' writes /etc/systemd/system/<unit> -> /dev/null, which outranks the fstab generator). /etc/fstab is untouched; pass --persist-swap-off to comment its swap entries out too."
  fi
  log "to restore swap on this host: systemctl unmask <the units masked above>; swapon -a (doc §13)"
}

persist_swap_off() {
  [[ -f /etc/fstab ]] || { warn "/etc/fstab not found; nothing to persist"; return 0; }
  local backup tmp before after
  backup="/etc/fstab.core-banking-$RUN_TS.bak"
  # The candidate is built first and the backup is taken only when it differs:
  # an unconditional 'cp -p' left one more /etc/fstab.core-banking-<ts>.bak on
  # every re-run of an already-converged host.
  tmp=$(mktemp)
  # Anchored on field 3 (fstype) only. Commented lines are passed through
  # untouched, and a real filesystem mounted at a path containing "swap"
  # (e.g. "UUID=... /var/swap ext4 ...") is never matched.
  awk -v ts="$RUN_TS" '
    /^[[:space:]]*#/ { print; next }
    NF >= 3 && $3 == "swap" { print "# disabled by core-banking bootstrap " ts ": " $0; next }
    { print }
  ' /etc/fstab >"$tmp"

  # awk's own record count, not wc -l: an /etc/fstab without a trailing newline
  # has one fewer wc line than the awk output, which fired this guard
  # spuriously - after swap was already off and before the stack started.
  before=$(awk 'END {print NR}' /etc/fstab)
  after=$(awk 'END {print NR}' "$tmp")
  if [[ $before -ne $after ]]; then
    rm -f "$tmp"
    die "refusing to rewrite /etc/fstab: line count changed ($before -> $after). /etc/fstab was not modified"
  fi
  if cmp -s "$tmp" /etc/fstab; then
    rm -f "$tmp"
    log "/etc/fstab already has no active swap entries (unchanged; no backup taken)"
  else
    cp -p /etc/fstab "$backup"
    install -m 0644 "$tmp" /etc/fstab
    rm -f "$tmp"
    log "commented out swap entries in /etc/fstab (backup: $backup)"
  fi
}

# --- 17. Profile transition: drain, record, start (§21.3) --------------
start_profile() {
  step "Starting profile '$PROFILE'"
  local before="$EVIDENCE_DIR/bootstrap-$RUN_TS-before.txt"
  # Not masked with '|| true': 'docker ps' exits 0 with an empty file on a
  # daemon error, which would leave the §21.3 transition record blank while the
  # run reports success (doc §6).
  docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
    --format '{{.Names}}\t{{.Image}}\t{{.Status}}' >"$before" ||
    die "could not record the component set before the transition ('docker ps' failed); is dockerd running? Inspect with: docker info"
  log "component set before the transition recorded in $before"
  if [[ -s $before ]]; then cat "$before"; fi

  # §21.3: drain first. The fixed project name is what makes this a transition
  # rather than two profiles' containers stacking on one host.
  log "draining project $COMPOSE_PROJECT"
  # Not masked with '|| true': starting a profile on top of a half-drained
  # project stacks two profiles on one 8 GiB host and invalidates every
  # measurement (§21.1, §21.3). Named volumes are deliberately preserved - a
  # restore drill needs empty volumes and removes them by hand (doc §7/§8).
  docker compose --project-name "$COMPOSE_PROJECT" down --remove-orphans ||
    die "draining project $COMPOSE_PROJECT failed; refusing to start '$PROFILE' on top of it. Inspect with: docker compose --project-name $COMPOSE_PROJECT ps -a"

  log "bringing up $PROFILE and waiting up to ${WAIT_TIMEOUT}s for readiness"
  # Compose-file contract for '--wait' (doc §6):
  #   * every long-running service declares a healthcheck - '--wait' reports a
  #     service without one as ready the instant it is running;
  #   * every one-shot job (Flyway, §21.3) is reachable as a
  #     'service_completed_successfully' dependency of a long-running service,
  #     or carries a 'profiles:' key and is run with 'compose run --rm' first.
  #     A one-shot service that is neither makes 'up --wait' exit non-zero the
  #     moment it exits 0, aborting the run here.
  docker compose "${COMPOSE_ARGS[@]}" up -d --remove-orphans --pull missing \
    --wait --wait-timeout "$WAIT_TIMEOUT"
}

# --- 18. Post-check ----------------------------------------------------
container_state_fields() {
  # '<status>|<restarting>|<restart count>|<exit code>' for one container.
  # 'docker inspect -f' writes a bare newline to STDOUT before it fails, so an
  # inline '|| printf missing|...' fallback is APPENDED to that newline and the
  # caller's 'read' - which takes the first line only - sees an empty record.
  # Capture, then replace: the sentinel must be the whole value.
  local out
  out=$(docker inspect -f '{{.State.Status}}|{{.State.Restarting}}|{{.RestartCount}}|{{.State.ExitCode}}' "$1" 2>/dev/null) || true
  [[ -n $out ]] || out='missing|false|0|0'
  printf '%s' "$out"
}

post_check() {
  step "Post-check"
  docker compose "${COMPOSE_ARGS[@]}" ps -a

  # -a so the after-set is comparable with the before-set, which is taken from
  # 'docker ps -a': without it the exited one-shot jobs silently disappear.
  local after="$EVIDENCE_DIR/bootstrap-$RUN_TS-after.txt"
  docker compose "${COMPOSE_ARGS[@]}" ps -a --format '{{.Name}}\t{{.Image}}\t{{.Status}}' >"$after" ||
    die "could not record the component set after the transition ('docker compose ps' failed); the §21.3 transition record would be incomplete"

  local names=()
  mapfile -t names < <(docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" --format '{{.Names}}')
  (( ${#names[@]} > 0 )) ||
    die "project $COMPOSE_PROJECT has no containers after 'up --wait' returned; the profile did not start"

  # 'up --wait' is not sufficient evidence that the stack is up:
  #   * a service with 'restart:' and no healthcheck can crash-loop and still be
  #     reported "Healthy" - it is momentarily running at every sample;
  #   * a container that exits non-zero shortly after 'up' returns is missed by
  #     a single status read.
  # So: two samples ~${CRASHLOOP_SAMPLE_SECONDS}s apart. A container is a crash
  # loop if it is 'restarting' at either sample or its RestartCount grew between
  # them. RestartCount > 0 on its own is NOT a failure - a service that legitimately
  # restarts once while a dependency starts ends stable with RestartCount=1.
  local -A sample_a=()
  local name status restarting restarts code
  local pstatus prestarting prestarts pcode
  local failures=() notes=()
  for name in "${names[@]}"; do
    sample_a[$name]=$(container_state_fields "$name")
  done
  log "sampling container state again in ${CRASHLOOP_SAMPLE_SECONDS}s to detect crash loops"
  sleep "$CRASHLOOP_SAMPLE_SECONDS"
  for name in "${names[@]}"; do
    IFS='|' read -r pstatus prestarting prestarts pcode <<<"${sample_a[$name]}"
    IFS='|' read -r status restarting restarts code <<<"$(container_state_fields "$name")"
    if [[ $status == missing || $pstatus == missing ]]; then
      # container_state_fields falls back to 'missing|false|0|0' when 'docker
      # inspect' fails. Those placeholders match no failure case below, so
      # without this branch a container removed (or a daemon lost) between the
      # name listing and the sample passes the post-check silently.
      failures+=("$name: 'docker inspect' could not read it (state $pstatus -> $status); it was removed, or dockerd became unreachable, during the post-check")
    elif [[ $restarting == true || $prestarting == true ]]; then
      failures+=("$name: crash-looping (restarting; RestartCount $prestarts -> $restarts)")
    elif [[ $restarts =~ ^[0-9]+$ && $prestarts =~ ^[0-9]+$ ]] && (( restarts > prestarts )); then
      failures+=("$name: crash-looping (RestartCount rose $prestarts -> $restarts over ${CRASHLOOP_SAMPLE_SECONDS}s)")
    elif [[ $code != 0 || $pcode != 0 ]]; then
      failures+=("$name: exited non-zero (exit code ${code:-$pcode}, status $pstatus -> $status)")
    else
      case $status in
        dead|created)
          failures+=("$name: container is '$status', not running") ;;
        exited)
          # One-shot migration jobs exit 0 by design (§21.3, doc §6): a note.
          notes+=("$name: Exited (0)") ;;
      esac
    fi
  done
  if (( ${#notes[@]} > 0 )); then warn "containers not running (expected for wired one-shot jobs): ${notes[*]}"; fi
  if (( ${#failures[@]} > 0 )); then
    die "the stack is not healthy after 'up --wait':
$(printf '  %s\n' "${failures[@]}")
Inspect with: docker compose ${COMPOSE_ARGS[*]} logs <service>"
  fi

  # The doc §6 contract - "every long-running service declares a healthcheck" -
  # is asserted here, and nowhere else: '--wait' reports a service WITHOUT one as
  # ready the instant its container is running, so an unhealthy stack passes
  # silently. --allow-missing-healthchecks downgrades this to a warning and is
  # recorded in the manifest.
  local unhealthy=() undeclared=() declared running health
  for name in "${names[@]}"; do
    running=$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || printf 'false')
    [[ $running == true ]] || continue
    declared=$(docker inspect -f '{{if .Config.Healthcheck}}{{if .Config.Healthcheck.Test}}{{index .Config.Healthcheck.Test 0}}{{end}}{{end}}' "$name" 2>/dev/null || printf '')
    if [[ $declared != CMD && $declared != CMD-SHELL ]]; then
      undeclared+=("$name")
      continue
    fi
    health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null || printf 'none')
    [[ $health == healthy ]] || unhealthy+=("$name ($health)")
  done
  if (( ${#undeclared[@]} > 0 )); then
    if (( ALLOW_MISSING_HEALTHCHECKS == 1 )); then
      warn "running containers with no CMD/CMD-SHELL healthcheck: ${undeclared[*]} (accepted because --allow-missing-healthchecks was given; 'up --wait' proved nothing about these services)"
    else
      die "these running containers declare no healthcheck: ${undeclared[*]}
'docker compose up --wait' reports a service without a healthcheck as ready the
instant its container is running, so their readiness was never proven. Doc §6
requires every long-running service to declare a 'healthcheck:' (a CMD form
works even for shell-less images). Add them to the base file or the overlay, or
re-run with --allow-missing-healthchecks for a non-evidence run."
    fi
  fi
  if (( ${#unhealthy[@]} > 0 )); then
    die "running containers that declare a healthcheck but are not healthy: ${unhealthy[*]}
Inspect with: docker compose ${COMPOSE_ARGS[*]} logs <service>"
  fi

  (( $(awk 'NR>1' /proc/swaps | wc -l) == 0 )) || die "swap became active during startup; this run cannot produce evidence"

  cat <<EOF

Profile '$PROFILE' is up under project '$COMPOSE_PROJECT'.
  log       : $LOG_FILE
  manifest  : $EVIDENCE_DIR/bootstrap-$RUN_TS.json
  before/after component sets: $EVIDENCE_DIR/bootstrap-$RUN_TS-{before,after}.txt

Next:
  docker compose ${COMPOSE_ARGS[*]} ps
  docker compose ${COMPOSE_ARGS[*]} logs -f <service>
Attach the manifest's compose config hash to every §21.16 resource report.
EOF
}


main() {
  parse_args "$@"
  require_root "$@"
  start_logging
  preflight
  # Before any network fetch: a badly skewed clock otherwise fails
  # 'curl https://download.docker.com' and apt-get update with opaque TLS
  # errors instead of this script's clear time-sync message.
  assert_time_sync
  configure_apt
  # Before the first apt call, not after the last one: apt-daily.service,
  # apt-daily-upgrade.service, unattended-upgrades and cloud-init are exactly
  # what holds the apt locks on a freshly booted host.
  disable_unattended_upgrades
  remove_conflicting_packages
  install_base_packages
  install_docker
  configure_docker_daemon
  # Only now is Docker's real data-root knowable, so re-run the storage gates
  # against it (preflight could only guess from /var/lib[/docker]).
  recheck_storage_root
  configure_sysctl
  configure_firewall
  resolve_repo
  resolve_compose_files
  bootstrap_secrets
  # write_evidence_manifest is also the early 'docker compose config' gate: it
  # must run before the swap step, so an invalid overlay fails before anything
  # irreversible (the host has already had conflicting packages removed, apt
  # frozen and ufw enabled by this point - see the header comment).
  # disable_swap then rewrites the manifest's swap_devices.
  write_evidence_manifest
  disable_swap
  refresh_manifest_swap_state
  start_profile
  post_check
}

main "$@"
