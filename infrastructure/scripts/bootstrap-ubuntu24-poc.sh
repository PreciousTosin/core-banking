#!/usr/bin/env bash
set -euo pipefail

# Bootstrap script for Ubuntu 24.04 single-VPS PoC (Modern Core Banking, PoC)
# - Disables swap (per PoC guidance)
# - Installs Docker Engine and Docker Compose plugin
# - Clones the core-banking repo and starts the PoC using the normal overlay by default
# - Accepts an optional profile: normal, concurrency, restore

REPO_DIR=${REPO_DIR:-/opt/core-banking}
PROFILE=${PROFILE:-normal}
GIT_REPO=${GIT_REPO:-https://example.com/your-org/core-banking.git}
BRANCH=${BRANCH:-main}

print() { echo -e "$*"; }

usage() {
  cat <<EOF
Usage: sudo ./infrastructure/scripts/bootstrap-ubuntu24-poc.sh [--repo <url>] [--branch <branch>] [--profile <normal|concurrency|restore>]
Defaults:
- repo: GIT_REPO env or https://example.com/your-org/core-banking.git
- branch: main
- profile: normal
EOF
}

require_root() {
  if [ "$EUID" -ne 0 ]; then
    print "This script must be run as root. Try: sudo $0";
    exit 1;
  fi
}

install_docker() {
  print "Installing Docker..."
  apt-get update
  apt-get install -y ca-certificates curl gnupg lsb-release
  mkdir -p /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
}

disable_swap() {
  print "Disabling swap (PoC requirement)."
  swapoff -a
  sed -i '/\s*swap\s/d' /etc/fstab || true
}

clone_repo() {
  mkdir -p "$REPO_DIR"
  if [ -d "$REPO_DIR/.git" ]; then
    print "Repo already cloned. Pulling latest..."
    git -C "$REPO_DIR" fetch --all --tags
  else
    git clone "$GIT_REPO" "$REPO_DIR"
  fi
}

start_poC() {
  local compose_file="docker-compose.$PROFILE.yml"
  if [ ! -f "$REPO_DIR/$compose_file" ]; then
    echo "Warning: $compose_file not found. Falling back to docker-compose.yml if present."
    if [ -f "$REPO_DIR/docker-compose.yml" ]; then
      compose_file="$REPO_DIR/docker-compose.yml"
    else
      echo "No compose file found. Please ensure a docker-compose overlay exists (normal/concurrency/restore)."
      exit 1
    fi
  fi

  echo "Starting PoC using $compose_file..."
  docker compose -f "$REPO_DIR/$compose_file" up -d
}

print_header() {
  echo "==========================================="
  echo "$1"
  echo "==========================================="
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --repo)
        GIT_REPO="$2"; shift 2;;
      --branch)
        BRANCH="$2"; shift 2;;
      --profile)
        PROFILE="$2"; shift 2;;
      --help|-h)
        usage; exit 0;;
      *)
        echo "Unknown argument: $1"; usage; exit 1;;
    esac
  done
}

main() {
  parse_args "$@"
  require_root
  print_header "Ubuntu 24.04 PoC Bootstrap" 
  disable_swap
  install_docker
  clone_repo
  # If a specific branch is desired, try to checkout after clone
  if [ -d "$REPO_DIR/.git" ]; then
    git -C "$REPO_DIR" fetch --all --tags
    git -C "$REPO_DIR" checkout "$BRANCH" || true
  fi
  start_poC
  docker ps -a
  print "PoC startup attempted. Check containers with: docker ps -a" 
}

main "$@"
