# Yano Wallet

A full-node Cardano desktop wallet. It runs **its own Yano node** rather than
talking to a third-party API, so balances, history and transaction submission
come from a chain you validated yourself.

> Your keys. Your node. Nothing in between.

- Software and **hardware** (Ledger) wallets, multi-account per CIP-1852
- Send/receive with native assets, staking, governance (CIP-1694) and voting
- **CIP-30 dApp connector** via a browser extension over Chrome Native Messaging
- Argon2id-encrypted vault, optionally protected by a **FIDO2 security key**
- A managed local node the wallet launches for you, or your own external node

## Requirements

- **Java 25+**
- A **Yano node distribution** — resolved automatically, see below

## Build and run

```bash
./gradlew build                 # compile + test
./gradlew fetchYanoNode         # download the pinned Yano node (~256 MB, once)
./gradlew :wallet-app:run       # launch the wallet
```

Useful run flags:

```bash
./gradlew :wallet-app:run --args="--network=preprod --node=managed"
./gradlew :wallet-app:run --args="--auto-connect"      # skip the picker and reconnect
./gradlew :wallet-app:run --args="--data-dir=/tmp/scratch-wallet"   # isolated state
```

Launching stops at the Connect screen with the last choice prefilled and starts
nothing until you click — a local node can sync for a long time, so that should
follow from a decision rather than from opening the app. `--auto-connect`
restores the old one-click behaviour.

Wallet data lives in `~/.yano-wallet/<network>/` (never point `--data-dir` at
`/tmp` — the OS reaps it and corrupts the node database).

## The Yano node dependency

The wallet does **not** depend on Yano as a library; there is nothing to link
against. The node is a separate process the wallet spawns and then talks to over
REST. What the build needs is an *artifact*: a released Yano **distribution**.

It must be the distribution, not a bare `yano.jar` — the node reads genesis from
`config/network/<network>/` in its working directory, and only `application.yml`
is baked into the jar.

The pinned version lives in `gradle.properties`:

```properties
yanoVersion = 0.1.0-pre13
```

Resolution order (first hit wins), which mirrors what `NodeLocator` does at
runtime:

| # | Source | When |
|---|--------|------|
| 1 | `-PyanoDist=/path` or `YANO_DIST` | developing against a local node build |
| 2 | `~/.gradle/yano-dist/yano-<version>/` | already downloaded |
| 3 | GitHub release, SHA-256 verified | first build on a new version |

So local and offline builds never hit the network, and CI downloads once per
version bump (cache that directory keyed on `yanoVersion`).

```bash
./gradlew yanoNodeInfo          # which distribution will be used
./gradlew fetchYanoNode         # download + verify + unpack
./gradlew yanoNodeChecksum      # print the SHA-256 to record in yano-node.lock
```

Binaries are **never committed** — the node distribution is ~256 MB, and each
version bump would live in git history forever.

### Working against a locally built node

```bash
# in the yano repo
./gradlew :app:build
# in this repo
YANO_NODE_JAR=/path/to/yano/app/build/yano.jar ./gradlew :wallet-app:run
```

`NodeLocator` derives the node's working directory by finding the nearest
ancestor containing `config/`, so both the in-repo layout
(`app/build/yano.jar` → `app/`) and a release layout
(`yano-1.2.3/yano.jar` → `yano-1.2.3/`) work unchanged.

## Packaging

```bash
./gradlew :wallet-app:jpackage           # portable app image (bundled JRE + node)
./gradlew :wallet-app:jpackageInstaller  # dmg / msi / deb
./gradlew :wallet-app:walletZip          # portable zip, bring your own Java 25+
```

jpackage cannot cross-compile — build each OS on its own machine. Add
`-PskipNodeBundle` for an external-node-only package.

## Modules

| Module | Role |
|---|---|
| `wallet-core` | keys, vault, accounts, tx building (CCL) |
| `wallet-node-client` | REST client for the Yano node |
| `wallet-node-launcher` | locates and supervises the managed node process |
| `wallet-hardware` | Ledger (USB-HID) and FIDO2 security keys |
| `wallet-ui` | JavaFX views; depends only on the async controller contract |
| `wallet-app` | assembly, packaging, probes |
| `wallet-connector-host` | CIP-30 bridge server |
| `wallet-connector-proxy` | zero-dependency Native Messaging relay |
| `wallet-connector` | the browser extension (not a Gradle module) |

Design decisions live in [`adr/`](adr/); known gaps in [`BACKLOG.md`](BACKLOG.md).

## License

MIT
