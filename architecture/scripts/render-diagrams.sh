#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repository_root="$(cd "$script_dir/../.." && pwd -P)"
tooling_dir="$repository_root/architecture/tooling"
temp_root="$(mktemp -d)"
install_dir="$temp_root/install"
output_dir=""
cleanup() {
  rm -rf -- "$temp_root"
}
trap cleanup EXIT
if [[ $# -gt 1 ]]; then
  echo "usage: $0 [output-directory]" >&2
  exit 2
fi
if [[ $# -eq 1 ]]; then
  output_dir="$1"
  mkdir -p -- "$output_dir"
else
  output_dir="$temp_root/output"
fi
mkdir -p -- \
  "$install_dir" \
  "$output_dir" \
  "$temp_root/npm-cache" \
  "$temp_root/puppeteer-cache" \
  "$temp_root/xdg-cache" \
  "$temp_root/xdg-config" \
  "$temp_root/xdg-data"
cp -- "$tooling_dir/package.json" "$tooling_dir/package-lock.json" "$install_dir/"
owned_env=(
  "npm_config_cache=$temp_root/npm-cache"
  "PUPPETEER_CACHE_DIR=$temp_root/puppeteer-cache"
  "XDG_CACHE_HOME=$temp_root/xdg-cache"
  "XDG_CONFIG_HOME=$temp_root/xdg-config"
  "XDG_DATA_HOME=$temp_root/xdg-data"
)
env "${owned_env[@]}" npm ci --prefix "$install_dir"
mmdc="$install_dir/node_modules/.bin/mmdc"
test -x "$mmdc"
mapfile -t sources < <(find "$repository_root/architecture/diagrams" -maxdepth 1 -type f -name '*.mmd' -print | LC_ALL=C sort)
test "${#sources[@]}" -gt 0
for source in "${sources[@]}"; do
  output="$output_dir/$(basename "${source%.mmd}").svg"
  env "${owned_env[@]}" "$mmdc" -i "$source" -o "$output"
done
