# ADR-044 — A native (GraalVM) wallet distribution with a bundled native Yano

**Status:** Gates cleared; **native build working on macOS arm64** (unlock, balance, Ledger
connect and Ledger-signed send all verified) — not yet shippable: see "Implementation status"
**Date:** 2026-08-19

---

## 0. Summary

1. **The two halves are coupled in one direction only.** A native wallet has no JVM, so it
   *must* bundle native Yano. A JVM wallet gains almost nothing from native Yano. There is no
   useful half-step — this is "stay JVM" or "go fully native".
2. **The usual justifications do not survive our own numbers.** Native Yano is *bigger* than the
   jar (332 MB vs 267 MB), node startup is swamped by the chainstate index rebuild (E20), and
   "no Java required" is **already true** for the installers because `jlinkRuntime` bundles a
   runtime.
3. **The one genuine benefit — needing no installed Java — is already available.** `jpackage`
   already produces a self-contained `--type app-image` with a bundled runtime; it is simply
   not published as a release asset. **Zipping it delivers the entire stated benefit with no
   new toolchain, no JDK downgrade, no Ledger risk and no licensing question** (§2.1). That is
   the recommendation; native is a later option, not the next step.
4. **All three gates are now cleared.** Using **Liberica NIK 25 Full** instead of GluonFX
   dissolves the feasibility gate (JDK 25, all four platforms, JavaFX static libs included) and
   the legal gate (no Gluon Substrate — the wallet **stays MIT**). The product gate is
   **tested and passes**: Ledger/JNA works under native-image with tracing-agent metadata
   (§G1). Native is therefore viable with no feature loss.
5. Good news: the launcher already supports a native node, and the release matrices already
   align exactly with Yano's native assets.

---

## 1. What we ship today

| artifact | needs Java installed? | how the node runs |
|---|---|---|
| dmg / msi / deb (`jpackage` + `jlinkRuntime`) | **no** — an OpenJDK runtime is bundled | `<bundled java> -jar yano.jar` |
| portable zip (`walletZip`) | **yes — Java 25** | user's `java -jar yano.jar` |

`release.yml` builds on macos-14 arm64, ubuntu-22.04 x86_64, ubuntu-22.04-arm arm64, and
windows-latest x86_64.

## 2. Why the obvious justifications do not hold

| claim | reality |
|---|---|
| "smaller" | native Yano is **332 MB** vs the **267 MB** jar. The app already bundles a JRE for JavaFX, which the JVM node reuses for free. Going native adds ~65 MB and saves only the node-only jlink modules (`java.sql`, `java.management`, `jdk.unsupported`) — roughly a wash, possibly worse |
| "faster startup" | measured **2.9 s** to listening for the native node on devnet. E20 measured **86 minutes** of account-history index rebuild on a first start. JVM startup is noise |
| "no Java needed" | already true for dmg/msi/deb via `jlinkRuntime` |
| **"the portable zip needs nothing installed"** | true — **but achievable today without native-image; see §2.1** |

### 2.1 The stated benefit is already available — recommended path

`wallet-app`'s `jpackage` task runs `--type app-image` with
`--runtime-image build/runtime` (the jlink runtime), and its own description reads *"Build the
portable Yano Wallet app image (bundled JRE + node)"*. **That output is already a
self-contained, no-Java-required, unzip-and-run distribution.** It simply is not published: the
release attaches the installers (dmg/msi/deb) and `walletZip`, which is the BYO-Java variant.

So the entire case for going native — "the portable zip should need nothing installed" — is
satisfied by **zipping the existing app-image** and publishing it as a release asset.

| | app-image zip | GraalVM native |
|---|---|---|
| needs Java installed | **no** | no |
| approximate size | ~500 MB (compresses well) | ~450 MB |
| new toolchain | **none** | Liberica NIK + GraalVM plugin + tracing-agent metadata |
| JDK constraint | keeps **25** | keeps **25** (NIK 25) |
| Ledger / JNA | **unaffected** | **at risk (G1 — the one open gate)** |
| licensing | **unchanged, MIT** | **unchanged, MIT** (via Liberica; not GluonFX) |
| effort | a Gradle `Zip` task and a release-workflow step | multi-phase, three gates |

**Recommendation: do §2.1 first.** It delivers the whole benefit for a fraction of the cost and
resolves G1–G3 by making them unnecessary. Revisit native only if a benefit appears that the
app-image cannot provide — startup latency that actually matters to users, or a size ceiling.

**Consequence:** the rest of this ADR describes what going native would require *if* that later
case is made. It is not the recommended next step.

## 3. What is already built

- **`NodeLaunchSpec.nativeBinary`** exists, and `ManagedNode.spawn()` already branches on it —
  passing `-D` flags ahead of the executable for the native case and ahead of `-jar` for the
  JVM case. Plumbed end to end, but **nothing ever sets it to `true`**.
- **The native zip has the same layout**: `yano` (executable) plus `config/network/<network>/`,
  so `NodeLocator.workingDirFor()` needs no change.
- **The launch contract is verified** against the real `macos-arm64` binary (2026-08-19). Run
  with exactly the arguments `ManagedNode.spawn()` already emits for the native branch:

  ```
  ./yano -Dquarkus.profile=devnet -Dquarkus.http.port=18099 \
         -Dyano.server.port=13099 -Dyano.storage.path=<scratch>
  ```

  All four took effect — `Listening on: http://0.0.0.0:18099`, `NodeServer … listening on port
  13099`, `Profile devnet activated`, and the chainstate was created at the given path. Started
  in **2.9 s**, shut down cleanly on SIGTERM in 0.002 s. So `spawn()`'s native branch needs no
  change.

  **One caveat surfaced by that run:** the image is built with the Quarkus profile `native`
  baked in, and it warns *"The profile 'native' used to build the native image is different from
  the runtime profile 'devnet'"*. Runtime config overrode correctly here, but **build-time-fixed
  Quarkus properties cannot be changed at runtime in a native image**. Whether every setting the
  wallet relies on for `preprod`/`preview`/`mainnet` is runtime-overridable is **not** yet
  established — see §8.4.
- **The platform matrices align exactly.** Yano publishes `yano-native-<ver>-{linux-x64,
  linux-arm64,macos-arm64,windows-x64}.zip` — the same four we already build. There is no
  macOS-Intel gap, because we do not target it either.

## 4. Gates — answer these before any build work

### G1. ~~Ledger via JNA under native-image~~ — **RESOLVED: it works**

Tested end to end on 2026-08-19 with **Liberica NIK 25** on macOS arm64, using a standalone probe
that enumerates HID devices through `hid4java 0.8.0 → jna 5.19.1`.

| build | result |
|---|---|
| JVM baseline | 31 HID devices enumerated |
| native, **no** metadata | **fails** — `UnsatisfiedLinkError: Native library (com/sun/jna/darwin-aarch64/libjnidispatch.jnilib) not found in resource path ()` |
| native, **with** tracing-agent metadata | **31 HID devices enumerated — identical to the JVM** |

The naive failure is the well-known JNA gap: JNA extracts its dispatch library from the jar at
runtime, and native-image does not embed that resource unless told to. The
**tracing agent solves it automatically** — one run of

```
java -agentlib:native-image-agent=config-output-dir=agent -cp <cp> HidProbe
```

produced `reachability-metadata.json` capturing **both** native libraries
(`com/sun/jna/darwin-aarch64/libjnidispatch.jnilib` and `darwin-aarch64/libhidapi.dylib`) plus 53
reflection entries. Rebuilding with `-H:ConfigurationFileDirectories=agent` then worked first
time. Build took 24.5 s; `--no-fallback` was used throughout, so this is a genuine native image
and not a fallback that silently requires a JVM.

**Consequence: hardware signing (ADR-034) survives a native build.** There is no product
trade-off to make, and no need to ship two artifacts or a feature-reduced native build.

Caveat carried forward: the metadata must be **regenerated whenever hid4java or JNA is bumped**,
and it is platform-specific — the captured resource path names `darwin-aarch64`, so each target
platform needs its own agent run or a merged config.

### G2. ~~Gluon's JDK / JavaFX pair vs ours~~ — **RESOLVED and verified end to end**

**Verified on macOS arm64, 2026-08-19** with `25.0.4.fx-nik` (Liberica NIK 25 **Full**) installed
via SDKMAN. A minimal JavaFX app (`Stage` + `Scene` + `Label`) was compiled, traced, and built
with `--no-fallback`:

| step | result |
|---|---|
| NIK Full contents | `native-image` **and** 7 `javafx.*` modules **and** JavaFX static libs (`libprism_common.a`, `libprism_es2.a`, `libprism_mtl.a`) |
| tracing agent | 69 reflection + 12 resource entries |
| native build | **succeeded in 36.6 s**, 66 MB binary |
| native run | **stage shown**; `javafx.graphics` loaded its native libs from inside the image |

Two practical notes for the real build: pass **`--enable-native-access=javafx.graphics`** (the
run warns without it, and the JVM packaging already passes `--enable-native-access=ALL-UNNAMED`),
and note the **`fx-nik`** SDKMAN identifier is the one that matters — `fx-librca` is the JDK with
JavaFX but **no** `native-image`, and `r25-nik` is NIK **Standard** with `native-image` but **no**
JavaFX. Only `25.0.4.fx-nik` has both.

#### Why Liberica rather than GluonFX (original analysis)

The original concern was that Gluon's GraalVM builds track JDK 21/23 while `wallet-app` sets
`sourceCompatibility = '25'`, and that Substrate bundles its own JavaFX.

**GluonFX is not the only route.** BellSoft's **Liberica Native Image Kit (NIK)** is a
GraalVM-based distribution whose **Full** variant bundles **LibericaFX (OpenJFX)**, driven by the
*official* GraalVM build-tools plugin (`org.graalvm.buildtools.native`) rather than anything of
Gluon's. Verified 2026-08-19:

| | Liberica NIK Full |
|---|---|
| JDK | 17, 21 **and 25** — **no downgrade needed** |
| JavaFX | LibericaFX, based on OpenJFX, included in the Full variant |
| platforms | macOS **aarch64**, Linux x64, Linux aarch64, Windows x64 — **exactly our four** |
| plugin | official GraalVM `native-gradle-plugin` (confirm its licence; expected permissive) |
| Gluon Substrate | **not used** |

So G2 dissolves: the wallet stays on **Java 25**, keeps its own JavaFX dependency management, and
uses a maintained toolchain. What remains is ordinary native-image work — collecting
reflection/resource metadata with the **tracing agent** for Jackson, BouncyCastle, CCL and
JavaFX, and wiring a per-platform CI build.

Reference: BellSoft's guide to building JavaFX native images with NIK.

### G3. ~~Gluon Substrate's GPLv2~~ — **resolved by not using Substrate**

Substrate was the whole legal problem: verified 2026-08-19 as **plain GPLv2 with no Classpath
Exception**, and its version intent is ambiguous (GitHub reports SPDX `GPL-2.0`; the `LICENSE`
file carries no "or later" clause; Gluon's own `build.gradle` names GPL v2 while linking to
**LGPL**-2.0 — evidently a copy-paste error). Dropping GluonFX removes it from the picture
entirely.

What remains linked into a Liberica-built binary is the **same licence category we already
ship**:

Verified against the Liberica NIK EULA (2026-08-19), which states NIK is **100% open source**,
that the open-source terms **prevail over the EULA**, and imposes **no commercial-use or
field-of-use restrictions**:

| component | licence | in the binary |
|---|---|---|
| **GraalVM** (native-image runtime) | **Apache 2.0** — permissive | yes — no copyleft at all |
| OpenJDK class library | GPLv2 **+ Classpath Exception** | yes — CE permits linking |
| **LibericaFX (OpenJFX)** | GPLv2 **+ Classpath Exception** (OpenJFX upstream; grouped under the EULA's OpenJDK terms) | yes — CE permits linking |
| `org.graalvm.buildtools.native` | permissive (confirm) | no — build only |
| Yano | MIT | yes (separate process) |
| **the wallet** | **stays MIT** | — |

Note the GraalVM half is **Apache 2.0**, not GPLv2+CE as an earlier draft of this ADR assumed —
permissive, and unambiguously MIT-compatible. The Classpath Exception on the OpenJDK/OpenJFX half
exists precisely to permit linking into a work distributed under our own terms, and
`jlinkRuntime` already bundles OpenJDK (GPLv2+CE) into today's installers. **Native image via
Liberica introduces no new licence class, and the wallet stays MIT.**

Two honest caveats:

- Reading the Classpath Exception to cover **AOT compilation** of the library into the binary is
  the standard interpretation the entire native-image ecosystem operates on, and BellSoft
  distributes NIK for exactly this purpose — but it *is* an interpretation, and should be signed
  off rather than assumed.
- **The Classpath Exception removes copyleft from *our* code; it does not remove GPLv2's
  source-availability obligation for the GPL'd components themselves.** Distributing binaries
  containing OpenJDK/OpenJFX requires offering the corresponding source for those components —
  see the compliance gap below.

#### Why "relicense the wallet as GPL" was never the answer

Recorded because it is the obvious first reaction, and it is a trap:

| wallet licence | conflicts with |
|---|---|
| **GPLv2** | **Jackson (Apache-2.0)** — the patent-termination clause is an "additional restriction" under GPLv2. Jackson is load-bearing and cannot be dropped |
| **GPLv3** | **Substrate, if GPLv2-only** — GPLv2-only and GPLv3 are incompatible |

Both GPL flavours conflict with something, so relicensing would have traded MIT away and *still*
not been clean. (Useful finding while checking: **`cardano-client-lib` is MIT**, not Apache-2.0.)

**Stay MIT.** Add the missing `LICENSE` file (below), and choose a toolchain that does not
require otherwise.

*None of this is legal advice.*

> **Compliance gap that already exists, independent of this ADR.** No `LICENSE`, `NOTICE`, or
> third-party-licence file ships in *any* current distribution: the repo has none, `jpackageArgs`
> passes no `--license-file`, and `wallet-app/src/dist/` contains only `README.txt` and the two
> launch scripts. But `jlinkRuntime` **already** bundles OpenJDK (GPLv2+CE) into every installer
> today, which carries a source-availability obligation and a notice requirement. This should be
> fixed for the *current* releases, not deferred to a native one: add the wallet's own `LICENSE`,
> ship a third-party notice listing the bundled runtime, and provide the written offer / upstream
> source link for the GPL'd components.
>
> **Separately: `yano-wallet` has no `LICENSE` file at all.** Yano is MIT (repo and bundled
> distribution); the wallet repo ships nothing. That should be fixed regardless of this ADR —
> compatibility cannot be assessed against an unlicensed project, and a consumer wallet needs a
> declared licence.

## 5. Code gaps beyond build configuration

| gap | today | needed |
|---|---|---|
| `NodeLocator.findNodeJar()` | looks for `yano.jar` | must also find `yano` / `yano.exe` |
| `nativeBinary` | hardcoded `false` in `autoDetectDevJar` | set from what was actually resolved |
| `resolveYanoDist` (`gradle/yano-node.gradle`) | requires `yano.jar` + `config/` | accept `yano` + `config/` |
| dist download | one URL, `yano-<ver>.zip` | **per-platform** asset `yano-native-<ver>-<os>-<arch>.zip` |
| `gradle/yano-node.lock` | one checksum per version | **checksum per platform per version** |
| executable bit | n/a | zip extraction must preserve or restore `+x` on the `yano` binary |
| macOS Gatekeeper | n/a | the Yano binary is **adhoc/linker-signed, not Developer-ID** — fine inside a signed `.app`, blocked for a downloaded portable zip. E8 does not cover this |

## 5a. Implementation status (2026-08-19)

**Working end to end on macOS arm64.** `./gradlew :wallet-app:nativeZip -PyanoNative` produces a
157 MB zip that runs with nothing installed:

```
yano-wallet-native-<ver>.zip
├── yano-wallet.sh / .bat   launchers (resolve symlinks; point at the bundled node)
├── yano-wallet             102 MB AOT binary
└── yano-node/              native Yano + config/network/
```

Verified: built with `--no-fallback`, run under `env -i` from an extracted zip, **zero errors**,
CIP-30 bridge up, `-Dyano.node.jar` correctly passed by the launcher.

| built | detail |
|---|---|
| `nativeAgent` / `nativeImage` / `nativeZip` tasks | `wallet-app/build.gradle`, `Exec`-based to match the existing jlink/jpackage idiom |
| per-platform native node | `-PyanoNative` in `gradle/yano-node.gradle`; artifact-keyed `yano-node.lock`; exec bit restored after unpack |
| `NodeLocator` | finds `yano`/`yano.exe` as well as `yano.jar`, and sets `NodeLaunchSpec.nativeBinary` |
| launchers | `wallet-app/src/dist-native/` |
| metadata | `wallet-app/src/main/resources/META-INF/native-image/reachability-metadata.json` |

### Verified working on macOS arm64 (2026-08-20)

Exercised through the real UI against a managed native preprod node: **unlock**,
**balance**, **hardware-wallet connect**, and **sending ADA signed on a Ledger**.
Not yet exercised: delegation, governance, minting, and dApp/CIP-30 signing.

Getting there took four rounds, each a *distinct* class of missing metadata. They are worth
knowing because each will recur per platform, and none fails at build time:

| # | symptom | cause | fix |
|---|---|---|---|
| 1 | wrong-password on a correct password | Jackson **deserialisation** of the vault. Serialisation (saving) needs getters; deserialisation needs a creator — separate registrations, so "create works, unlock doesn't" | agent run driven through `WalletProbe` unlock |
| 2 | `Error getting utxos` | CCL's Retrofit **dynamic proxies** + `Utxo` DTO | agent run against a **funded** address |
| 3 | `Unsupported JNI version 0x0` | jnidispatch's `JNI_OnLoad` calls JNI `FindClass("java/nio/ByteBuffer")`; JNI is a **separate registry** from reflection | a `jni` section (46 entries) |
| 4 | `getFieldOrder() … names ([ptr]) … declared ([])` | JNA `Structure` fields read reflectively; `HidDeviceStructure` unregistered | explicit `fields` from the jar via `javap` |

**Three traps that made this slow, all worth avoiding next time:**

- **Empty results hide missing registrations.** The first UTxO capture ran against a wallet with
  **zero** UTxOs, so Jackson never instantiated a `Utxo` and the gap stayed invisible. Capture
  against real data.
- **`allDeclaredMethods`/`allDeclaredConstructors` permit *lookup*, not *invocation*** in this
  schema. The agent lists every method explicitly; hand-written `all*` flags silently do less.
  Prefer agent output over hand-editing.
- **A probe that stops short gives false assurance.** `--hid-probe` originally only called
  `enumerate()`, which never *opens* a device, so it reported OK while Connect still failed.
  A probe must go as far as the real path does.

**The single highest-leverage change** was adding headless diagnostics to the binary itself —
`--hid-probe` and `--utxo-probe=<address>` — turning a rebuild-plus-GUI-click loop into a
one-second command against the same image and code paths. Both remaining bugs fell within
minutes of that. Do this first on any new platform.

### Not shippable yet — in priority order

1. **The metadata is incomplete.** It came from a **~50-second unattended agent run** covering
   startup only. Unexercised paths fail at **runtime, not build time** — this already bit once
   (`MacGestureSupport`, loaded on the first trackpad gesture). A real session is required:
   unlock a wallet, open History, send a transaction, plug in a Ledger, switch networks.
   **Until that is done, treat the binary as a demo, not a release candidate.**
2. **Only macOS arm64 has been built or run.** The other three platforms are untested, and
   metadata is platform-specific (captured paths name `darwin-aarch64`).
3. **No CI.** `release.yml` does not build or attach native artifacts.
4. **Signing/notarisation** (extends E8). The bundled Yano binary is **adhoc/linker-signed**, so a
   downloaded zip will be blocked by Gatekeeper.
5. **`LICENSE` + third-party notice** — a gap in *today's* releases too (§G3).

### Traps found while building this, worth not re-learning

- **`nativeImage` originally declared outputs but no inputs**, so Gradle skipped rebuilds and a
  stale binary was tested and briefly believed to pass. Inputs are declared now; a suspiciously
  fast `nativeZip` should still prompt a timestamp check.
- **Zip drops the POSIX exec bit** unless set explicitly — for both the wallet and the node.
- **The agent needs NIK's `java`**; a plain JDK 25 fails with "Could not find agent library
  native-image-agent".
- **JavaFX must come from LibericaFX, not the `org.openjfx` Maven artifacts** — those carry
  `.dylib`s for the JVM and collide with the modules the static libs are built against. The
  native classpath filters them and passes `--add-modules javafx.controls`.

---

## 6. Phases

| phase | deliverable | gate |
|---|---|---|
| **N-1** | **Publish a zip of the `jpackage` app-image** (§2.1) — a `Zip` task over `build/dist/Yano Wallet[.app]` plus a `release.yml` step; consider retiring `walletZip` | a downloaded zip runs on a machine with no Java |
| ~~**N0**~~ | ~~**G1 spike**~~ — minimal native image enumerating HID devices | **DONE 2026-08-19 — passes** (§G1) |
| **N1** | Add a `LICENSE` (MIT) + third-party notice and GPL source offer to **current** distributions (§G3 compliance gap); confirm the GraalVM plugin's licence | shipped artifacts carry correct notices |
| **N2** | Build the wallet on the JVM path against **Liberica NIK 25 Full**; collect reflection/resource metadata with the tracing agent | wallet runs unchanged; metadata committed |
| **N3** | Per-platform native-Yano resolution: `yano-node.gradle`, per-platform lock entries, `NodeLocator`, `nativeBinary = true`, exec bit | managed node starts from a native binary on one platform |
| **N4** | Liberica NIK native build of the wallet on **one** platform (macos-arm64) | app launches, unlocks a wallet, shows balance |
| **N5** | Remaining three platforms in CI; native zip per platform | all four build and start |
| **N6** | Signing/notarisation for native artifacts (extends E8) | Gatekeeper-clean download on macOS; SmartScreen story on Windows |

**N-1 is the recommended work.** N0 onward apply only if native is pursued afterwards; N0 and
N1 are cheap and gate everything else, so do not start N3 before both are answered.

## 7. Consequences

- **The JVM path stays.** Native is an *additional* artifact, not a replacement, until it has
  shipped and been exercised. The dmg/msi/deb are unaffected.
- **The release matrix grows.** Four platforms × (JVM installer + portable + native) is a large
  CI surface. Consider dropping the BYO-Java portable zip once the native zip exists — it is the
  artifact native replaces, and keeping both is the worst of both.
- **AOT builds are slow** (minutes per platform) and will dominate CI time.
- **Reflection config becomes a maintenance surface.** No FXML and no reflection in wallet code
  helps a great deal, but Jackson, BouncyCastle and CCL all use reflection and will need config
  that must be revisited on every dependency bump.
- **Debuggability drops.** Stack traces, JFR and attaching a debugger all get worse — relevant
  because the node-startup path (E20) was diagnosed by reading logs and process state.

## 8. Open questions

1. **Does anything remain that the app-image zip (§2.1) cannot deliver?** If not, the answer is
   "ship N-1 and stop", which is the expected outcome of this ADR.
2. ~~G1 — Ledger/JNA under native-image~~ — **resolved, passes** (§G1).
3. The missing wallet `LICENSE` and third-party notices — **a gap in today's releases**, not
   just a native concern (§G3). Plus the GraalVM build-tools plugin licence.
4. ~~Does native Yano accept the flags `ManagedNode` passes?~~ — **verified** (§3): profile,
   HTTP port, N2N port and storage path all take effect. **What remains open** is the
   build-time-profile question it exposed: the image bakes in the `native` profile, so any
   Quarkus property fixed at build time cannot be overridden at runtime. Confirm that the
   per-network profiles (`preprod`/`preview`/`mainnet`) and `yano.upstream.*` are all
   runtime-overridable in the native image before N3 — a silently ignored setting would put the
   managed node on the wrong network or the wrong relays.
5. Does the native wallet still support the CIP-30 native-messaging host (ADR-035/039), whose
   installer writes a manifest naming an executable path?
6. GraalVM CE vs Oracle GraalVM — if Gluon's build derives from Oracle GraalVM rather than CE,
   the distribution terms differ (GFTC, not GPLv2+CE) and G3 must cover that too.
