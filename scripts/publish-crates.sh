#!/usr/bin/env bash
set -euo pipefail

release_version="${1:?usage: publish-crates.sh VERSION}"
: "${CARGO_REGISTRY_TOKEN:?CARGO_REGISTRY_TOKEN is required}"

crate_exists() {
    local crate="$1"
    curl --fail --silent --show-error \
        --user-agent "MoonLightBridge release automation (https://github.com/MoonL1ghtProject/MoonLightBridge)" \
        "https://crates.io/api/v1/crates/${crate}/${release_version}" \
        >/dev/null 2>&1
}

wait_for_crate() {
    local crate="$1"
    local attempt
    for attempt in {1..30}; do
        if crate_exists "$crate"; then
            return 0
        fi
        sleep 10
    done
    echo "Timed out waiting for ${crate} ${release_version} in crates.io" >&2
    return 1
}

publish_crate() {
    local crate="$1"
    if crate_exists "$crate"; then
        echo "${crate} ${release_version} already exists; skipping"
        return 0
    fi

    cargo publish --locked --package "$crate"
    wait_for_crate "$crate"
}

./scripts/check-release-version.sh "$release_version"
publish_crate moonlight-bridge-protocol
publish_crate moonlight-bridge-codegen
publish_crate moonlight-bridge-server
publish_crate moonlight-bridge-sentry
