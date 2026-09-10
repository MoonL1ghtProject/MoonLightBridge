#!/usr/bin/env bash
set -euo pipefail

expected_version="${1:?usage: check-release-version.sh VERSION [TAG]}"
expected_tag="${2:-}"
cargo_version="$(sed -n '/^\[workspace.package\]/,/^\[/s/^version = "\([^"]*\)"/\1/p' Cargo.toml)"
gradle_version="$(sed -n 's/^version=//p' gradle.properties)"

if [[ -z "$cargo_version" || -z "$gradle_version" ]]; then
    echo "Could not read the Cargo or Gradle version" >&2
    exit 1
fi

if [[ "$expected_version" != "$cargo_version" || "$expected_version" != "$gradle_version" ]]; then
    echo "Release version mismatch: requested=$expected_version cargo=$cargo_version gradle=$gradle_version" >&2
    exit 1
fi

if [[ "$expected_version" == *-SNAPSHOT ]]; then
    echo "Release version must not end with -SNAPSHOT" >&2
    exit 1
fi

if [[ ! "$expected_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Stable release version must use MAJOR.MINOR.PATCH: $expected_version" >&2
    exit 1
fi

if [[ -n "$expected_tag" && "$expected_tag" != "v$expected_version" ]]; then
    echo "Tag $expected_tag must exactly match v$expected_version" >&2
    exit 1
fi

echo "Release version verified: $expected_version"
