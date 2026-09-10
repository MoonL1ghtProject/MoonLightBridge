#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
socket_dir="$(mktemp -d)"
socket_path="$socket_dir/moonlight-bridge.sock"
cert_dir="$(mktemp -d)"
backend_pid=""
test_pid=""

stop_backend() {
    if [[ -n "$backend_pid" ]]; then
        kill "$backend_pid" 2>/dev/null || true
        wait "$backend_pid" 2>/dev/null || true
        backend_pid=""
    fi
}

cleanup() {
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
cargo test --workspace

cargo run --quiet --package moonlight-bridge-example-backend >"$project_dir/moonlight-bridge-backend.log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if (echo > /dev/tcp/127.0.0.1/38191) 2>/dev/null; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:integrationTest -PmoonlightBridgeTransport=tcp
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-example-api:typedIntegrationTest
stop_backend

MOONLIGHT_BRIDGE_UNIX_PATH="$socket_path" cargo run --quiet --package moonlight-bridge-example-backend >>"$project_dir/moonlight-bridge-backend.log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if [[ -S "$socket_path" ]]; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:integrationTest \
    -PmoonlightBridgeTransport=unix -PmoonlightBridgeSocketPath="$socket_path"
stop_backend

openssl req -x509 -newkey rsa:2048 -sha256 -days 1 -nodes \
    -subj "/CN=MoonLightBridge Test CA" -keyout "$cert_dir/ca.key" -out "$cert_dir/ca.crt" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" -addext "extendedKeyUsage=serverAuth" \
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
    cargo run --quiet --package moonlight-bridge-example-backend >>"$project_dir/moonlight-bridge-backend.log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if (echo > /dev/tcp/127.0.0.1/38192) 2>/dev/null; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:integrationTest \
    -PmoonlightBridgeTransport=tls -PmoonlightBridgeKeyStore="$cert_dir/client.p12" \
    -PmoonlightBridgeTrustStore="$cert_dir/truststore.p12"
stop_backend

cargo run --quiet --package moonlight-bridge-example-backend >>"$project_dir/moonlight-bridge-backend.log" 2>&1 &
backend_pid=$!
for _ in {1..50}; do
    if (echo > /dev/tcp/127.0.0.1/38191) 2>/dev/null; then break; fi
    sleep 0.1
done
"$project_dir/gradlew" --no-daemon :java:moonlight-bridge-client:reconnectIntegrationTest \
    -PmoonlightBridgeMarkerDirectory="$cert_dir" &
test_pid=$!
for _ in {1..200}; do
    if [[ -f "$cert_dir/ready" ]]; then break; fi
    sleep 0.05
done
[[ -f "$cert_dir/ready" ]]
stop_backend
for _ in {1..200}; do
    if [[ -f "$cert_dir/disconnected" ]]; then break; fi
    sleep 0.05
done
[[ -f "$cert_dir/disconnected" ]]
cargo run --quiet --package moonlight-bridge-example-backend >>"$project_dir/moonlight-bridge-backend.log" 2>&1 &
backend_pid=$!
wait "$test_pid"
test_pid=""
