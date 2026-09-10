#!/usr/bin/env bash
set -euo pipefail

release_version="${1:?usage: package-release.sh VERSION [TAG]}"
release_tag="${2:-}"

./scripts/check-release-version.sh "$release_version" "$release_tag"

# The leaf crates can be fully verified before anything is public. Cargo needs
# dependency crates to exist in the registry before it can verify dependants,
# so those are checked as workspace sources and their package file lists are
# inspected here; the registry performs the final package verification in the
# dependency-ordered publication job.
cargo package --locked --allow-dirty --package moonlight-bridge-protocol
cargo package --locked --allow-dirty --package moonlight-bridge-codegen
cargo package --locked --allow-dirty --package moonlight-bridge-server --list >/dev/null
cargo package --locked --allow-dirty --package moonlight-bridge-sentry --list >/dev/null

./gradlew prepareJavaRelease

echo "Release packages prepared for $release_version"
