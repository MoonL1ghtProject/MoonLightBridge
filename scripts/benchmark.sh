#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
socket_dir="$(mktemp -d)"
socket_path="$socket_dir/expj.sock"
backend_pid=""

cleanup() {
    if [[ -n "$backend_pid" ]]; then
        kill "$backend_pid" 2>/dev/null || true
        wait "$backend_pid" 2>/dev/null || true
    fi
    rm -f "$socket_path"
    rmdir "$socket_dir" 2>/dev/null || true
}
trap cleanup EXIT

cd "$project_dir"
cargo build --release --package rust-backend

"$project_dir/target/release/rust-backend" >"$project_dir/expj-benchmark.log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if (echo > /dev/tcp/127.0.0.1/38191) 2>/dev/null; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:expj-client:performanceBenchmark \
    -PexpjBenchmarkEndpoint="tcp://127.0.0.1:38191"
kill "$backend_pid"
wait "$backend_pid" 2>/dev/null || true

EXPJ_UNIX_PATH="$socket_path" "$project_dir/target/release/rust-backend" \
    >>"$project_dir/expj-benchmark.log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if [[ -S "$socket_path" ]]; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:expj-client:performanceBenchmark \
    -PexpjBenchmarkEndpoint="unix:$socket_path"
