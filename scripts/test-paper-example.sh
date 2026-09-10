#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
run_dir="$(mktemp -d)"
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
project_version="$(sed -n 's/^version=//p' gradle.properties)"
cargo build --package moonlight-bridge-test-plugin-backend
cargo run --quiet --package moonlight-bridge-test-plugin-backend >"$backend_log" 2>&1 &
backend_pid=$!

for _ in {1..50}; do
    if (echo > /dev/tcp/127.0.0.1/38201) 2>/dev/null; then break; fi
    sleep 0.1
done

"$project_dir/gradlew" :examples:paper-test-plugin:backendSmokeTest
"$project_dir/gradlew" :examples:paper-test-plugin:shadowJar

echo "Plugin: $project_dir/examples/paper-test-plugin/build/libs/paper-test-plugin-$project_version.jar"
