# Pterodactyl deployment

Pterodactyl game servers run in isolated Docker containers. Inside a server,
`127.0.0.1` refers to that server's container, not to the Wings node.

MoonLightBridge accepts these endpoint forms:

```text
unix:/home/container/.moonlight-bridge/backend.sock
tcp://172.18.0.1:38191
tcp://moonlight-bridge.internal.example:38191
tls://bridge.example.com:38191
```

## 1. Backend in the Minecraft container

Use a Unix socket. This is the default target architecture when the plugin
extracts and starts its own Rust binary:

```text
unix:/home/container/.moonlight-bridge/backend.sock
```

It requires no allocation or exposed port. The backend process must remove only
its own stale socket before binding and restrict directory permissions.

## 2. Backend on the Wings node

There are two options.

### Shared mount plus Unix socket

This keeps traffic off TCP. A node administrator creates a dedicated host
directory, allows it in Wings `allowed_mounts`, and mounts that directory into
the Minecraft container. The host backend binds its socket inside the host
directory; Java opens the corresponding container path.

Use a separate directory per server. Both processes need compatible filesystem
permissions, and the mount must not be read-only. Do not expose one shared
socket directory to unrelated tenants.

### Private TCP through the bridge gateway

Bind the host backend to the Pterodactyl bridge interface and connect to that
interface from the container. `172.18.0.1` is the documented default, but it is
configurable and must not be hardcoded by the SDK. Pass the actual address in an
environment variable or plugin configuration.

Open only the backend port on the `pterodactyl0` interface. Be aware that a
broad firewall rule can make the backend reachable by every game-server
container on the node.

## 3. Backend in another Pterodactyl container

Prefer a private TCP allocation on the same node. Connect through the node's
bridge address and the backend server's allocated port. Pterodactyl's documented
proxy setup uses the same pattern for communication between isolated game
servers.

Do not depend on container IP addresses: they are deployment details and may
change after recreation. Do not assume Docker DNS aliases exist for independently
managed Pterodactyl servers.

## 4. Backend on another machine

Use a DNS name or private overlay-network address:

```text
tcp://moonlight-bridge-backend.internal:38191
```

Use `tls://` for cross-host production deployment. MoonLightBridge requires
mTLS: Java validates the backend certificate and hostname, while Rust validates
a client certificate against its configured client CA. Plain `tcp://` must
remain on a trusted private network.

Reconnect never replays an interrupted request. Its result is unknown: the
backend may have committed a mutation just before the connection disappeared.
Generated APIs will later permit retries only for read-only methods or mutations
carrying an idempotency key.

## Configuration recommendation

The eventual Paper SDK should read one value and avoid environment detection:

```text
MOONLIGHT_BRIDGE_ENDPOINT=unix:/home/container/.moonlight-bridge/backend.sock
```

or:

```text
MOONLIGHT_BRIDGE_ENDPOINT=tcp://172.18.0.1:38191
```

Explicit configuration is more reliable than guessing the Docker gateway.

## Pterodactyl references

- [Minecraft server networking](https://pterodactyl.io/community/games/minecraft.html)
- [Wings custom network interfaces](https://pterodactyl.io/wings/1.0/configuration.html)
- [Pterodactyl mounts](https://pterodactyl.io/guides/mounts.html)
