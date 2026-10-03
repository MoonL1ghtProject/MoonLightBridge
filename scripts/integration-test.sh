#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
socket_dir="$(mktemp -d)"
socket_path="$socket_dir/moonlight-bridge.sock"
cert_dir="$(mktemp -d)"
backend_log="$cert_dir/backend.log"
backend_pid=""
test_pid=""

stop_backend() {
    if [[ -n "$backend_pid" ]]; then
        kill "$backend_pid" 2>/dev/null || true
        wait "$backend_pid" 2>/dev/null || true
        backend_pid=""
    fi
}

wait_backend() {
    local transport="$1" target="$2"
    for _ in {1..300}; do
        kill -0 "$backend_pid" 2>/dev/null || { echo "Backend exited before readiness" >&2; return 1; }
        if [[ "$transport" == unix ]]; then
            [[ -S "$target" ]] && return 0
        elif (echo > /dev/tcp/127.0.0.1/"$target") 2>/dev/null; then
            return 0
        fi
        sleep 0.1
    done
    echo "Backend did not become ready: $transport $target" >&2
    return 1
}

cleanup() {
    local status=$?
    if [[ "$status" -ne 0 && -f "$backend_log" ]]; then
        tail -n 100 "$backend_log" >&2
    fi
    if [[ -n "$test_pid" ]]; then
        kill "$test_pid" 2>/dev/null || true
        wait "$test_pid" 2>/dev/null || true
    fi
    stop_backend
    rm -f "$socket_path"
    rmdir "$socket_dir" 2>/dev/null || true
    rm -rf -- "$cert_dir"
}
trap cleanup EXIT

cd "$project_dir"
cargo test --workspace --locked
cargo build --locked --package moonlight-bridge-example-backend
RUSTDOCFLAGS="-D missing-docs" cargo doc \
    -p moonlight-bridge-protocol -p moonlight-bridge-server -p moonlight-bridge-codegen --no-deps

"$project_dir/target/debug/moonlight-bridge-example-backend" >"$backend_log" 2>&1 &
backend_pid=$!
wait_backend tcp 38191
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:integrationTest -PmoonlightBridgeTransport=tcp
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-example-api:typedIntegrationTest
stop_backend

MOONLIGHT_BRIDGE_UNIX_PATH="$socket_path" "$project_dir/target/debug/moonlight-bridge-example-backend" >>"$backend_log" 2>&1 &
backend_pid=$!
wait_backend unix "$socket_path"
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:integrationTest \
    -PmoonlightBridgeTransport=unix -PmoonlightBridgeSocketPath="$socket_path"
stop_backend

openssl req -x509 -newkey rsa:2048 -sha256 -days 1 -nodes \
    -subj "/CN=MoonLightBridge Test CA" -keyout "$cert_dir/ca.key" -out "$cert_dir/ca.crt" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost" -addext "extendedKeyUsage=serverAuth" \
    -keyout "$cert_dir/server.key" -out "$cert_dir/server.csr" >/dev/null 2>&1
openssl x509 -req -sha256 -days 1 -copy_extensions copy -in "$cert_dir/server.csr" \
    -CA "$cert_dir/ca.crt" -CAkey "$cert_dir/ca.key" -CAcreateserial \
    -out "$cert_dir/server.crt" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -subj "/CN=moonlight-bridge-test-client" \
    -addext "extendedKeyUsage=clientAuth" -keyout "$cert_dir/client.key" \
    -out "$cert_dir/client.csr" >/dev/null 2>&1
openssl x509 -req -sha256 -days 1 -copy_extensions copy -in "$cert_dir/client.csr" \
    -CA "$cert_dir/ca.crt" -CAkey "$cert_dir/ca.key" -CAcreateserial \
    -out "$cert_dir/client.crt" >/dev/null 2>&1
openssl pkcs12 -export -name moonlight-bridge-client -passout pass:changeit \
    -inkey "$cert_dir/client.key" -in "$cert_dir/client.crt" -certfile "$cert_dir/ca.crt" \
    -out "$cert_dir/client.p12" >/dev/null 2>&1
keytool -importcert -noprompt -alias moonlight-bridge-ca -storepass changeit \
    -file "$cert_dir/ca.crt" -keystore "$cert_dir/truststore.p12" >/dev/null 2>&1

MOONLIGHT_BRIDGE_ADDRESS="127.0.0.1:38192" MOONLIGHT_BRIDGE_TLS_CERT="$cert_dir/server.crt" \
MOONLIGHT_BRIDGE_TLS_KEY="$cert_dir/server.key" MOONLIGHT_BRIDGE_TLS_CLIENT_CA="$cert_dir/ca.crt" \
    "$project_dir/target/debug/moonlight-bridge-example-backend" >>"$backend_log" 2>&1 &
backend_pid=$!
wait_backend tcp 38192
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:integrationTest \
    -PmoonlightBridgeTransport=tls -PmoonlightBridgeKeyStore="$cert_dir/client.p12" \
    -PmoonlightBridgeTrustStore="$cert_dir/truststore.p12"
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:tlsSecurityTest \
    -PmoonlightBridgeKeyStore="$cert_dir/client.p12" \
    -PmoonlightBridgeTrustStore="$cert_dir/truststore.p12"
stop_backend

"$project_dir/target/debug/moonlight-bridge-example-backend" >>"$backend_log" 2>&1 &
backend_pid=$!
wait_backend tcp 38191
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:reconnectIntegrationTest \
    -PmoonlightBridgeMarkerDirectory="$cert_dir" &
test_pid=$!
for _ in {1..1200}; do
    if [[ -f "$cert_dir/ready" ]]; then break; fi
    sleep 0.05
done
[[ -f "$cert_dir/ready" ]]
stop_backend
for _ in {1..1200}; do
    if [[ -f "$cert_dir/disconnected" ]]; then break; fi
    sleep 0.05
done
[[ -f "$cert_dir/disconnected" ]]
"$project_dir/target/debug/moonlight-bridge-example-backend" >>"$backend_log" 2>&1 &
backend_pid=$!
wait_backend tcp 38191
wait "$test_pid"
test_pid=""
