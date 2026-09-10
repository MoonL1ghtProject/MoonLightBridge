#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_dir="$(mktemp -d)"
socket_path="$run_dir/moonlight-bridge.sock"
backend_log="$run_dir/backend.log"
backend_pid=""

cleanup() {
    local status=$?
    if [[ "$status" -ne 0 && -f "$backend_log" ]]; then
        tail -n 100 "$backend_log" >&2
    fi
    if [[ -n "$backend_pid" ]]; then
        kill "$backend_pid" 2>/dev/null || true
        wait "$backend_pid" 2>/dev/null || true
    fi
    rm -rf -- "$run_dir"
}
trap cleanup EXIT

cd "$project_dir"
cargo build --release --package moonlight-bridge-example-backend

"$project_dir/target/release/moonlight-bridge-example-backend" >"$backend_log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if (echo > /dev/tcp/127.0.0.1/38191) 2>/dev/null; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:performanceBenchmark \
    -PmoonlightBridgeBenchmarkEndpoint="tcp://127.0.0.1:38191"
kill "$backend_pid"
wait "$backend_pid" 2>/dev/null || true

MOONLIGHT_BRIDGE_UNIX_PATH="$socket_path" "$project_dir/target/release/moonlight-bridge-example-backend" \
    >>"$backend_log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if [[ -S "$socket_path" ]]; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:performanceBenchmark \
    -PmoonlightBridgeBenchmarkEndpoint="unix:$socket_path"
