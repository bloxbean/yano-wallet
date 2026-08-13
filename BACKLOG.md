# Yano Wallet — Backlog (Epics)

Known gaps and planned work, kept out of the ADRs so status is scannable.
Everything **core** is device/on-chain-verified as of 2026-07-17 (see ADR-033/034/035
trackers); these epics are the delta to "no known gaps + publicly releasable".

| # | Epic | Status | Notes |
|---|------|--------|-------|
| E1 | **Hardware reward withdrawal — device test** | code + tests done | Needs rewards to exist; plan: devnet run (`HardwareStakeService.buildWithdrawal`). |
| E2 | **DRep unregister — click-through** | built | Deposit-reclaim flow (`draftDRepDeregistration`); never exercised on-device. Cheap win. |
| E3 | **Hardware signData (CIP-8)** | not built | Ledger app has a message-signing instruction; dApps needing `signData` on hardware currently get a clear error. |
| E4 | **dApp txs with certs/withdrawals on hardware** | not built | `LedgerTxTranslator` rejects them with a plain message; needed for staking/governance dApps with Ledger. Cert translation exists for wallet-built txs — reuse it. |
| E5 | **CIP-95 governance for dApps** | not built | `getPubDRepKey`/`getRegisteredPubStakeKeys` etc.; advertise via `getExtensions`. Enables govtool-style dApps. |
| E6 | **M5 distribution + transport hardening** | designed (ADR-035) | Chrome/Firefox/Edge listings (pinned key → stable id), privacy policy, **Native Messaging** host + manifest (installer-registered), retire the localhost WS for released builds. Pairs with code-signing. |
| E7 | **Release engineering — first real run** | workflow written | Never run: tag `v<version>` to let `release.yml` build all four platforms, verify Win/Linux packages (only macOS built locally), run the portable BYO-Java zip on a clean Java-25 machine. `build.yml` (compile + test, no packaging) runs on every push to main and PR. |
| E8 | **Code-signing + notarization** | researched | macOS Developer ID ($99/yr) + notarytool; Windows Azure Trusted Signing (~$10/mo); wire into CI gated on secrets. Until then: unsigned-install docs. |
| E9 | **Multi-account support (CIP-1852)** | **built — ADR-037**, needs manual verification | MA-M1/M2/M3 done: grouped list, sidebar switcher, software + hardware add-account, restore discovery. Deferred polish: per-row balances, rename account, auto-scan at end of restore. |
| E10 | **HW-M5: more devices** | not built | Trezor (USB) and/or Keystone (QR, airgapped) behind the same `HardwareWalletService` SPI. |
| E11 | **HW-M6: Speculos CI** | not built | Ledger emulator in CI so the device protocol gets automated regression tests. |
| E12 | **YubiKey/FIDO2 vault factor** | designed (ADR-036) | Envelope v3, HMAC challenge-response mixed into the KDF; Y-M1 YubiKey OTP, Y-M2 FIDO2 `hmac-secret`. |
| E13 | **CIP-45 (cross-device connect)** | deferred by decision | Revisit if dApp adoption grows or a mobile/companion story appears (ADR-035 records the rationale). |
| E14 | **M3 leftovers** | minor | Multi-tab behavior, bridge lifecycle polish, per-account CIP-30 nuances (after E9). |
| E15 | **Backend flavors — yaci-store / Yaci DevKit** | planned — ADR-038 | Money path already works (wallet is Blockfrost-shaped; CCL's BF services never call `/genesis`). Needs: flavor probe (`/status` 404 ⇒ yaci-store), pill via `/blocks/latest`, DRep via `/governance-state/dreps/{id}`, devkit preset. Blocker: **yaci-store exposes no protocol magic** → fingerprint workaround + refuse-mainnet rule. |
| E16 | **yaci-store: expose network magic** | **filed — [yaci-store#1018](https://github.com/bloxbean/yaci-store/issues/1018)** | Asks for `/api/v1/genesis` (or `network_magic` on `/network`), plus an indexer-lag indicator as a secondary. Filed generically — **it does not mention this wallet** (stealth). Once shipped: restores the plain magic check for both flavors, drops ADR-038 §2b entirely, and removes the hardcoded "devnet == magic 42" assumption. |
| E17 | **Managed-node resilience: guard the chainstate, and tell the user how to recover** | not built | Earned by a real corruption on 2026-08-13 (see below). Four parts, prevention first: **(a) data-dir lock** — a `FileLock` on `<dataDir>/.lock` held for the wallet's lifetime, so a second wallet says "Another Yano Wallet is using ~/.yano-wallet; close it, or use `--data-dir=…`" instead of opening the same RocksDB. **(b) node-version guard** — stamp the node version into the chainstate on creation and refuse to open it with an older node. **(c) failure classification + recovery offer** — `ManagedNode.failureReason()` hands back a raw string today; classify the node's exit output (`Cannot repair epoch nonce state`, startup `IllegalStateException`, `SIGSEGV` in `librocksdbjni`, port in use, disk full) and offer the matching action. For an unusable chainstate that is *"Delete local chain data and resync"*, stating the cost (preprod ≈ 33 GB, ~40 min at the measured ~3,000 blocks/s) and the reassurance that makes it a safe click: **chainstate is derived data — keys live in `<dataDir>/<network>/wallets/vault.json` and cannot be lost by resyncing**. Rename to `chainstate.corrupt-<timestamp>` rather than delete, so the evidence survives. **(d) `node.log` append/rotate** — `ManagedNode.spawn()` uses `redirectOutput(File)`, which truncates on every start; on 2026-08-13 a second node wiped the very log being used to diagnose a sync problem. |

| E18 | **Upstream relay failover + per-network relay settings** | designing | The wallet launches the node with a single upstream (`yano.remote.*`), so a slow or dead relay stalls the sync with nowhere to go — see the incident notes. Yano already recovers on `NO_PROGRESS` / `BODY_FETCH_STUCK`, it just needs alternatives. Plan: `yano.upstream.mode=trusted-failover` with `bulk-source: single-trusted` (failover, **not** parallel download), 2+ default relays per network in `WalletNetwork`, passed as `-Dyano.upstream.peers[i].{host,port,priority}` from `ManagedNode.spawn()`. Custom relays take priority 0..n with defaults appended as fallback, plus a "use only my relays" opt-out; per-network override persisted like `ConnectorSettingsStore`, removable to revert to defaults; **`protocol-magic` never user-editable** (a wrong-network relay must fail at handshake, never sync a foreign chain into the chainstate); takes effect on restart. Reachable from the Connect screen *and* Advanced Settings — a dead relay must be fixable before a failed start, and node readiness is `GET /status == 200` (not sync progress) so the user can always get in. Needs a sync-status indicator that distinguishes **at tip / catching up / not syncing** — today they look identical, which is also what made a healthy at-tip preprod look "very slow". Deferred, off by default, advanced-only: **peer discovery** — Yano already defaults `discovery.peer-snapshot-urls` to `https://book.play.dev.cardano.org/environments/<network>/peer-snapshot.json` (verified live 2026-08-13: mainnet 153 KB/470 pools, preprod 13 KB/52, preview 20 KB/80, each carrying `NetworkMagic`), so it is a flag flip rather than a hosting job. It is also the only answer to hardcoded relays rotting, since those files are on-chain-registered relays and work from a cold start. Wants testing first, and it changes a desktop wallet's network footprint. |

Ordering is not priority; pick by need. Cheap verification wins: E1, E2, E7.

## Incident notes

**2026-08-13 — mainnet chainstate corrupted by a concurrent, downgraded node.**
The release wallet (node `0.1.0-pre12`) was syncing mainnet when a dev wallet
(`./gradlew :wallet-app:run`) was started against the *same* default data dir.
Two compounding faults: two nodes on one RocksDB, and the dev build silently
using **`0.1.0-pre8`** — `resolveYanoDist` ranks a project-local `.yano-node/`
above the pinned release, and that link pointed at a jar built 2026-07-15, older
than even the worktree source beside it. Result: `Cannot repair epoch nonce
state: missing local block body 0 while replaying to 905`, then a `SIGSEGV` in
`librocksdbjni`. The dev node also truncated the release node's `node.log`
(same data dir → same log path), destroying the sync measurement in progress.
Mitigated by disabling the stale link (`.yano-node/yano.jar` →
`yano.jar.pre8-stale-disabled`), so dev and release now run the same node.
E17 is the durable fix. Related: the build only logs the resolved node when
resolution *fails* — a line naming the resolved jar and version at startup would
have made the downgrade obvious the first time.

**Mainnet sync is peer-bound, not resource-bound.** Measured the same night on a
128 GB machine, all runs on node `0.1.0-pre12` from an empty chainstate:

| run | time to first block | rate |
|---|---|---|
| mainnet 23:59 | 110 s | ~10 blocks/s — bursts of 100 blocks in 6 ms, then **0.0 % CPU for ~48 s** |
| preprod 23:55 | 0.6 s | ~3,000 blocks/s sustained |
| mainnet 00:12 | 6 s | **~5,165 blocks/s sustained**, no stalls, 113–147 % CPU |

The machine load was identical across all three (~535 % CPU of unrelated
devnet-cluster nodes, load average 8), so the 500× swing between the two mainnet
runs is **not** CPU, heap or packaging — no `-Xmx` is set anywhere, and at 128 GB
the JVM default is ~32 GB. The node does 5,000+ Byron blocks/s when fed; when it
is not, it idles at 0 % CPU waiting on `backbone.cardano.iog.io:3001`. What
changed upstream between 23:59 and 00:12 is unknown — transient congestion, new
client throttling, or a different host behind that DNS name. Hence E18: the
wallet ran with exactly one upstream and no alternative to fail over to.
