# Yano Wallet

A full-node Cardano desktop wallet. It runs **its own Yano node** rather than
talking to a third-party API, so balances, history and transaction submission
come from a chain you validated yourself.

> Your keys. Your node. Nothing in between.

---

> [!WARNING]
> **Test networks only — do not use this wallet with real funds.**
>
> Yano Wallet is under active development and has not been audited. Use it with
> a local development network (**Yaci DevKit**, **Yano DevNet**) or a public
> test network (**Preprod**, **Preview**), where ADA has no value.
>
> **Do not use it on Mainnet, and do not restore a recovery phrase that holds
> real funds** — entering those words here puts them at risk. Create a fresh
> wallet for testing instead.
>
> Provided "as is", without warranty of any kind. You are responsible for any
> loss. See [LICENSE](LICENSE).

---

- Software and **hardware** (Ledger) wallets, multi-account per CIP-1852
- Send/receive with native assets, staking, governance (CIP-1694) and voting
- **CIP-30 dApp connector** via a browser extension over Chrome Native Messaging
- Argon2id-encrypted vault, optionally protected by a **FIDO2 security key**
- A managed local node the wallet launches for you, or your own external node

**New here?** [GETTING-STARTED.md](GETTING-STARTED.md) is the user-facing guide:
download a release, run the wallet, install the browser extension, and connect a
dApp. It ships inside both the native and portable zips. The rest of this file
is for people building from source.

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

Everything the wallet owns lives under its data directory — `~/.yano-wallet/`
by default, or wherever `--data-dir` points. That includes the CIP-30 browser
connector (`<data-dir>/connector/`), so two wallets started with different data
directories no longer contend for one socket. The browser manifest holds an
absolute path to the host script, so re-run **Install browser connector** after
moving the app or switching data directory.

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

## Native build (GraalVM)

Produces an AOT-compiled wallet bundled with the **native** Yano node — a zip
that needs nothing installed: no Java, no JRE. See ADR-044.

### Toolchain

Requires **Liberica NIK Full**, the only GraalVM distribution shipping both
`native-image` and JavaFX. Two similarly-named things are *not* it:

| | `native-image` | JavaFX |
|---|---|---|
| Liberica JDK Full (`*.fx-librca`) | no | yes |
| Liberica NIK Standard (`*-nik`) | yes | no |
| **Liberica NIK Full** (`*.fx-nik`) | **yes** | **yes** |

Download it from
<https://bell-sw.com/pages/downloads/native-image-kit/> (choose **Full**, JDK 25),
or install it however you like. **The build does not look for it** — you point at
it explicitly, so nothing here depends on a particular package manager:

```bash
export NIK_HOME=/path/to/bellsoft-liberica-vm-full-...
# or per invocation: ./gradlew ... -PnikHome=/path/to/nik
```

### Build

```bash
./gradlew :wallet-app:nativeImage                # binary only -> build/native/
./gradlew :wallet-app:nativeZip -PyanoNative     # full portable zip -> build/dist/
```

`-PyanoNative` switches the bundled node to the platform's **native** Yano.
Without it you get the JVM `yano.jar`, which the native wallet has no JVM to run.

### Reachability metadata

Native images fail on missing reflection/JNI metadata at **runtime, not build
time**. Metadata lives in
`wallet-app/src/main/resources/META-INF/native-image/` and is regenerated by
running the app under the tracing agent:

```bash
./gradlew :wallet-app:nativeAgent   -PnativeAgentArgs="--node=managed --network=preprod --auto-connect"
```

Exercise the paths you care about, then quit normally (not `kill -9`, or the
agent will not flush). Re-run after bumping Jackson, BouncyCastle, JNA/hid4java
or CCL — and once per platform, since captured paths are architecture-specific.

Two headless diagnostics exist because the failures they catch are otherwise
reachable only by clicking through the UI:

```bash
./yano-wallet --hid-probe                                   # USB HID / Ledger
./yano-wallet --utxo-probe=<addr> --base-url=<node>/api/v1/  # CCL utxo path
```

**Platform coverage is 3, not 4.** BellSoft publishes NIK Full for linux-amd64,
macos-aarch64 and windows-amd64 — but for linux-aarch64 only NIK *Standard*
(no JavaFX), so **linux-arm64 has no native build** and ships via jpackage.

### CI

| workflow | trigger | output |
|---|---|---|
| `release.yml` | tag `v*` | JVM installers + BYO-Java zips (4 platforms) |
| `native-release.yml` | tag `v*` | native zips (3 platforms) |
| `dev-release.yml` | manual | both, as workflow artifacts only — no Release |

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

[MIT](LICENSE)
