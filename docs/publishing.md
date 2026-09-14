# Publishing MoonLightBridge

MoonLightBridge releases one semantic version across Cargo, Maven and GitHub. Java libraries are
published to Maven Central and mirrored to GitHub Packages. Public Rust
libraries are published to crates.io. A temporary Maven Central setup problem does not block the
other registries or the GitHub Release.

Registry releases are immutable. Never reuse a version, even if a publication job fails
halfway through; fix the job and let its idempotent steps continue, or increment the
version when an already-published artifact itself is wrong.

## Published artifacts

Maven coordinates use the `ru.moonlightproject` group:

- `moonlight-bridge-java` — recommended universal Java 21+ runtime;
- `moonlight-bridge-paper` — Paper/Folia adapter over the universal runtime;
- `moonlight-bridge-client` — low-level transport and asynchronous RPC SPI;
- `moonlight-bridge-framework` — compatibility aggregate for 0.1.x consumers;
- `moonlight-bridge-gradle-plugin` and the `ru.moonlightproject.bridge` marker.

Private implementation code is bundled and relocated inside the universal runtime. It is not a
separate Maven coordinate, a supported consumer entry point, or a GitHub Release artifact.

crates.io receives these packages in dependency order:

1. `moonlight-bridge-protocol`;
2. `moonlight-bridge-codegen`;
3. `moonlight-bridge-server`;

Examples and generated example APIs have `publish = false` and are never uploaded.

## One-time registry setup

For Maven Central, create a Central Portal account, verify the `ru.moonlightproject`
namespace using the `moonlightproject.ru` domain, generate a Central user token, and
publish the public GPG key used to sign releases.

Create a protected GitHub Environment named `release`. Add these Environment secrets:

| Secret | Value |
|---|---|
| `CRATES_IO_TOKEN` | crates.io API token scoped to the MoonLightBridge crates |
| `MAVEN_CENTRAL_USERNAME` | username from the Central Portal user token |
| `MAVEN_CENTRAL_PASSWORD` | password from the Central Portal user token |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored secret GPG key |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | GPG key passphrase |

`GITHUB_TOKEN` is supplied by Actions and has package-write access in the release job.
Require a reviewer on the `release` Environment so a pushed tag cannot publish without
an explicit approval.

## Prepare a release

Set the same version in `Cargo.toml`, `gradle.properties`, and every versioned local Cargo
dependency. Then run:

```bash
./scripts/check-release-version.sh 0.2.2
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --locked
./gradlew check
./scripts/package-release.sh 0.2.2
```

`package-release.sh` performs a complete Cargo package verification for independent
crates. Dependent crates cannot be fully packaged until their new local dependencies
exist on crates.io, so the script validates their file lists and the workspace build;
the release job publishes and verifies them one by one.

Review generated POMs under each Java module's `build/publications` directory. They must
contain sources, Javadoc, license, developer, SCM and issue-tracker metadata.

## Release

Commit the version, merge it to `main`, then create and push the exact tag:

```bash
git tag -s v0.2.2 -m "MoonLightBridge 0.2.2"
git push origin v0.2.2
```

Only tags shaped like `vMAJOR.MINOR.PATCH` start `.github/workflows/release.yml`. The
workflow checks that the tag, Cargo version and Gradle version match, reruns the complete
quality suite, prepares packages, waits for approval, publishes crates.io and GitHub Packages,
then creates a GitHub Release with JARs and SHA-256 checksums.

Maven Central is a separate release job controlled by `MAVEN_CENTRAL_ENABLED=true`, allowing a
failed registry upload to be diagnosed independently from crates.io and GitHub Packages.

If GitHub Packages alone fails after verification, run `Retry Java GitHub Packages` for the existing
tag. The recovery workflow verifies that the tag version matches the sources and never republishes
Cargo crates or contacts Maven Central.

Do not run `cargo publish` manually for the workspace: its dependency order and registry
propagation waits are encoded in `scripts/publish-crates.sh`.

## GitHub Packages

GitHub Packages is a mirror, not the recommended public download source, because GitHub
requires authentication even when consuming public Maven packages. To use it locally,
add the repository and credentials backed by a classic token with `read:packages`:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/moonl1ghtproject/moonlightbridge")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
            password = providers.gradleProperty("gpr.key").orNull
        }
    }
}
```

Keep `gpr.user` and `gpr.key` in the user's `~/.gradle/gradle.properties`, never in the
repository.
