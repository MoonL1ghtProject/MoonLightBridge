#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
backend_pid=""

cleanup() {
    if [[ -n "$backend_pid" ]]; then
        kill "$backend_pid" 2>/dev/null || true
        wait "$backend_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT

cd "$project_dir"
cargo build --package expj-test-plugin-backend
cargo run --quiet --package expj-test-plugin-backend >"$project_dir/expj-paper-test-backend.log" 2>&1 &
backend_pid=$!

for _ in {1..50}; do
    if (echo > /dev/tcp/127.0.0.1/38201) 2>/dev/null; then break; fi
    sleep 0.1
done

"$project_dir/gradlew" :examples:paper-test-plugin:backendSmokeTest
"$project_dir/gradlew" :examples:paper-test-plugin:shadowJar

echo "Plugin: $project_dir/examples/paper-test-plugin/build/libs/paper-test-plugin-0.1.0-SNAPSHOT.jar"
