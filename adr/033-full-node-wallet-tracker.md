# ADR-033 Tracker: Full-Node Desktop Wallet

Progress and per-milestone learnings for
[ADR-033](033-full-node-desktop-wallet.md). Update after each milestone.

## Status summary

| Milestone | Status | Exit criteria |
|---|---|---|
| M1 core reboot (wallet-core, wallet-node-client, probe) | **done (devnet)** | probe green: create/restore/balance/send on devnet ✅; preprod deferred (needs synced node) |
| M2 node wallet APIs | not started | history/status/rewards/SSE live, client stubs replaced |
| M3 desktop UI | not started | send/receive/delegate cycle via UI on preprod |
| M4 packaging | not started | signed installers from CI |
| M5 CIP-30 connector | not started | real dApp connect/sign/submit |
| M6 governance depth | not started | |
| M7 hardware/hardening | not started | |

## M1 — Core reboot (2026-07-12)

**Landed** (branch `feat/full_wallet`, under `wallet/`):
- `wallet-core` ported from `feat/wallet_mvp` with: vault envelope v2
  (**Argon2id** m=256MiB/t=3/p=2 + AES-256-GCM; v1 PBKDF2 vaults readable and
  auto-upgraded on unlock), network-scoped `StoredWalletRepository`
  (`network()` accessor; `createAccount(seedId, name, passphrase)` replaces
  the raw-mnemonic variant; no non-vault restore path), `WalletNetwork.DEVNET`
  fixed to protocol magic 42 (was preprod's). 31 tests.
- `wallet-node-client`: `YanoNodeBackend` composes CCL `BFBackendService`
  against Yano's `/api/v1` (UtxoSupplier / ProtocolParamsSupplier /
  TransactionProcessor incl. `evaluateTx` → `/utils/txs/evaluate`);
  `YanoNodeClient` for Yano-specific `/status`, `/genesis` (network-magic
  verification), tx-on-chain check. 12 tests against an in-process stub.
- `wallet-app`: headless `WalletProbe` (status/create/restore/list/balance/
  send --wait) + gradle `:wallet-app:probe` task.
- Build: modules under top-level `wallet/` (flat names + projectDir remap),
  all in `nonLibraryModules` (not published). New catalog entries: javafx,
  CCL core-api/transaction-spec/hd-wallet/cip30, bcprov 1.83, slf4j-simple.

**E2E evidence (devnet)**: create Alice/Bob → faucet-fund 1000 ADA → balance
via gap-limit scan → send 25 ADA (QuickTx build/sign, Scalus-validated
submit, confirmed on-chain, fee 175445) → balances exact → restore from
mnemonic in fresh dir reproduces address + balance.

**Learnings / plan adjustments:**
1. **CCL BF-backend compatibility confirmed empirically** — no adapter layer
   needed beyond base-URL + placeholder project id. `BFEpochService`
   resolves `epochs/latest` then `epochs/{n}/parameters`, so both routes are
   load-bearing for the wallet (add to M2 parity tests).
2. **Devnet restarts regenerate `shelley-genesis.json` `systemStart`**; a
   stale `app/chainstate` from a previous genesis aborts startup with
   "Genesis staking bootstrap marker mismatch" and then masks itself behind
   RocksDB lock errors on the REST surface. Wallet's node-launcher (M3) must
   detect this and offer a chainstate reset for devnet.
3. **Preprod probe deferred**: requires a synced preprod node (hours of sync
   or a chainstate backup). Re-run the probe on preprod when M2 testing
   brings a preprod node up anyway.
4. **Mnemonics must never transit argv** — probe grew `--mnemonic-file`;
   the UI must use the same discipline (no mnemonics in logs/process args).
5. `isTxOnChain` doubles as confirmation check only because there is no
   mempool visibility; M2's `/txs/{hash}/status` replaces it.

**Review pass (8 finder angles → 8 verified findings, all addressed):**
- Vault: seed-material copies now zeroized (payload carries `byte[]`, JSON
  field name unchanged for v1/v2 compat — verified an old vault still
  unlocks); Argon2 params from the file are bounded (memoryKb ≤ 1 GiB,
  t ≤ 128, p ≤ 32) so a tampered vault fails cleanly instead of OOM-ing.
- Atomic writes: index/pending stores use unique temp files + cleanup
  (fixed-`.tmp` concurrent-writer corruption).
- `NodeStatus.utxoIndexCaughtUp()` requires a known tip (fresh node reported
  lag 0 before indexing anything).
- Probe: confirmation polling survives transient node errors; `--ada`
  validation; hex decode via CCL `HexUtil`; JSON output via Jackson; send now
  records into `PendingTransactionStore` (DRAFTED→PENDING→CONFIRMED/FAILED),
  giving it a live consumer + e2e coverage.
- Gap-limit trap documented in `WalletBalanceService` Javadoc (verified:
  cannot be fixed client-side; CCL's `HDWalletAddressIterator` inherits the
  same `isUsedAddress` proxy).

**Deferred debt (tracked for M2/M3):**
- `WalletBalanceService` gap-limit scan uses "has unspent UTXO" as the
  used-address signal — restored wallets with fully-spent early addresses
  under-scan. Fixed by M2 address-history endpoint.
- **M3 design item**: introduce a `WalletService`/`WalletRuntimeController`
  facade (wallet-core) so probe and UI share ONE money path (unlock → draft →
  submit → pending-record → confirm); refactor `PendingTransaction`'s six
  16-arg copy constructors to with-ers, drop the never-assigned `SUBMITTED`
  status or wire it, stop persisting "N recipients" in `toAddress`.
- `YanoNodeBackend`/`YanoNodeClient` are per-connect objects; fine for the
  probe, but the M3 launcher must hold ONE long-lived backend per node (JDK
  HttpClient thread pools leak if constructed per operation).
- Balance scan is sequential per address (20+ round-trips); M2's
  payment-credential/history endpoints collapse it to one query.
- Reward/tx history not surfaced by probe (no node API yet — M2).
- wallet-core `META-INF/native-image` reflect-config carried from MVP,
  unreviewed — revisit in M4.

## M2 — Node wallet APIs (2026-07-12, DONE: implemented + reviewed + fixed)

**Review pass (8 finder angles → 8 findings, 7 fixed, 1 documented+deferred):**
- Per-family last-applied cursors (tx-events vs address-tx) so a
  later-enabled family backfills via reconcile instead of being skipped by
  the shared cursor — this is what makes enabling `%wallet` on an EXISTING
  synced node actually build the address index. Migration fallback for
  pre-cursor stores; regression tests both directions.
- Retention pruning never touches TYPE_ADDRESS_TX/TYPE_REWARD rows
  (retention-epochs bounds staking history only).
- Reward rows: phase-scoped keys (PHASE_REWARDS vs PHASE_POOLREAP no longer
  collide at a boundary slot); appendRewardRows is fail-open (an optional
  index can never abort the epoch-boundary reward commit); pool-deposit
  refunds now typed REFUND (were mislabeled LEADER upstream).
- L1EventFanout: subscription latch only set on success (a transient bus
  error no longer permanently degrades SSE to heartbeats); per-subscription
  offer is atomic under the two producer threads.
- Address summary returns 503 instead of a silently-truncated balance for
  >10k-UTXO addresses.
- Canonical `AddressKeyUtil` in core-api: single-parse scope derivation
  (was 3 bech32 decodes per address on block apply); UtxoKeyUtil delegates,
  so the UTXO and address-tx indexes can never disagree on hashing.
- Documented+deferred: `/txs/{hash}/status` reports `unknown` for
  deeply-settled txs whose outputs were spent and pruned (contract on
  TxStatusDto; proper fix = tx→block index, follow-up). Confirmations are
  depth-style (tip=0), consistent with BlockResource/Blockfrost.
- Cleanups: /accounts/{stake}/transactions reuses AddressTxDto; shared
  block-header helper in TransactionResource; SSE error payloads via
  Jackson. Deferred cleanups: shared pagination/order util for resources,
  Yano delegate-by-default decorator base (M3), MemPoolQuery split from
  TxGateway, keyset pagination for deep history pages.

### Implementation detail (as landed)

**Landed:**
- **Tx status + mempool visibility**: `TxGateway.isTransactionInMemPool` (default
  method; RuntimeNode → TxSubsystem → MemPool.contains). New
  `GET /txs/{hash}/status` → `in_block{block,slot,confirmations,block_time}` |
  `pending` | `unknown` — always 200 for a well-formed hash.
- **Address-tx history index**: extended `AccountHistoryStore` with
  `TYPE_ADDRESS_TX` rows in the existing `account_history` +
  `account_history_delta` CFs (delta-tracked rollback for free). One row per
  (scope, credential, tx); scopes = address-hash / payment-cred / stake-cred.
  Inputs resolved via `UtxoState.getUtxoSpentOrUnspent` (UTXO store applies at
  order 100, history at 112). Config `yano.account-history.address-tx-enabled`.
  Routes: `GET /addresses/{addr}/transactions` (+`use_payment_credential`),
  `GET /addresses/{addr}` summary, `GET /accounts/{stake}/transactions`.
- **Reward history**: `EpochRewardCalculator.creditReward` buffers
  `RewardHistoryEntry`s; `commitRewardBatch` appends `TYPE_REWARD` rows into
  the SAME WriteBatch (atomic with credits), keyed by boundary slot (rollback
  via slot-scan; no per-block delta — the boundary block owns that key).
  Route: `GET /accounts/{stake}/rewards` (Blockfrost `{epoch, amount, pool_id,
  type}`). Gate `yano.account-history.rewards-enabled` (was a dead flag).
- **L1 SSE**: `GET /events?topics=block,rollback,tx` — `L1EventFanout` (single
  sync bus subscription, never throws/blocks on the apply thread; per-client
  bounded queue, drop-oldest) exposed via `NodeEventStream` (core-api) →
  `Yano.eventStream()` → CDI bean; SSE resource on a virtual thread with 15s
  heartbeats. Contract: poll-reducer, not a guaranteed feed.
- **Profiles**: new `%wallet` profile; `%devnet` now enables account-history +
  address-tx + rewards so e2e/probe exercise them.
- **Wallet client**: `WalletBalanceService` gap decision now asks
  `UtxoSupplier.isUsedAddress` — CCL's BF backend maps that to
  `GET /addresses/{addr}/transactions`, so the M1 spent-out under-scan trap is
  FIXED against an M2 node (fallback to old proxy on older nodes).

**E2E evidence (devnet)**: send → `/txs/{hash}/status` in_block with
confirmations; address/account/stake history all return the tx; summary
aggregates funds; SSE delivered 98 block events + the tx event live;
balance scan green through the isUsedAddress path. Unit: 6 new store tests
(scopes, dedup, spent-out, rollback, reward rows round-trip + rollback,
disabled gates); full suites green (core-api 118, ledger-state 261+6,
runtime 846).

**Learnings:**
1. **JAX-RS routing trap**: a class-level `@Path("addresses")` out-matches
   UtxoResource's `@Path("/")` and 404s `/addresses/{addr}/utxos` — this is
   WHY UtxoResource uses a `/` class path with absolute method paths. New
   address routes follow the same convention.
2. **Yano decorator trap**: interface default methods on `Yano` (like the new
   `eventStream()`) are silently swallowed by decorators (`DevnetYano`) until
   each adds a delegating override — cost us a debugging round ("events
   disabled" on devnet). Consider a delegate-by-default decorator base.
3. Devnet faucet `POST /devnet/fund` injects UTXOs directly (no on-chain tx),
   so faucet funds never appear in address-tx history — correct but surprising.
4. Reward-boundary e2e deferred: devnet epoch shift needs past-time-travel
   mode and first real rewards land at the epoch-2 boundary; covered by unit
   tests now, exercised by the epoch-transition regression skills later.

**Deferred (M2 follow-ups):**
- `order` param still ignored on `/addresses/{addr}/utxos` (needs store-level
  desc iteration; wallet fetches all pages anyway).
- Asset metadata, tx-metadata (`/txs/{hash}/metadata`), datum-by-hash
  endpoints (ADR items 6-8).
- `/accounts/{stake}/addresses` (needs address-string storage per stake cred).
- Retention pruning (`retention-epochs`) applies to address-tx/reward rows
  like all history rows — wallet-serving nodes must keep retention 0 (full
  history); flag in %wallet docs.
- MIR/proposal-refund reward_rest paths not yet recorded as reward-history
  rows (MIR history exists separately via /mirs); pool-deposit refunds
  currently typed LEADER upstream in the calculator.

## M3+ — Managed node launcher + native asset send (2026-07-12)

Two follow-ups requested after the first M3 pass; both implemented + verified
on devnet (review in progress).

**Managed node (one-click, ADR-033 A3 realized):**
- New `wallet-node-launcher` module: `ManagedNode` spawns the node as a
  supervised CHILD PROCESS (not in-process — crash isolation + keeps the node
  native-imageable), health-polls `/status` to READY, captures logs, stops
  gracefully. `NodeLocator` finds `app/build/yano.jar` (or `YANO_NODE_JAR`);
  `FreePort` auto-picks a free REST + N2N port so the managed node NEVER
  collides with a default Yano on 7070/13337 (or, as found on this machine,
  Docker on 8090). Devnet wipes its chainstate per launch (ephemeral chain);
  real networks persist under `<data-dir>/<network>/node/chainstate`.
- **Bug found + fixed during test**: `-D` sysprops must precede `-jar` on the
  java command line (anything after `-jar` is a program arg) — the first
  attempt ran as prod/7070/magic-1 because the flags were ignored.
- `WalletBackendManager` resolves a `WalletConnectionConfig` (MANAGED |
  EXTERNAL) into a live backend, launching/supervising the managed node,
  rebuilding repository+backend+`WalletService`, persisting the choice to
  `<data-dir>/connection.json`. Wallet exit stops the node (shutdown hook).
- **UX decision (as asked): connection is configurable IN the UI.** New
  `ConnectScreen` (first-run + reconnect): network picker + "Run a local node
  (recommended)" vs "Connect to my node" (URL). Managed is the default —
  zero-config. `DefaultWalletUiController` is now connection-driven (builds
  the backend lazily via the manager); CLI `--node=managed|external` pre-seeds
  for power users/harness.
- Verified: launched with NO external node → wallet auto-started a devnet node
  on a free port (52130), magic 42, isolated chainstate, connected, and shut
  it down cleanly on exit; default 7070 untouched.

**Native asset send:**
- Send screen gains an asset picker showing ADA + each native-asset balance
  (decoded asset name when printable); `draftSend(unit, amount, …)` routes ADA
  (decimal) or native-asset (integer quantity, min-ADA auto-attached by CCL)
  through the same draft→review→submit path. `WalletService.draftMint` added
  (mint your own tokens under a single-sig policy) + probe `mint` command.
- Verified end-to-end on devnet: minted 5000 YANOTEST → balance shows the
  asset → sent 1200 via the UI controller path → receiver holds exactly 1200
  with ~1.16 ADA min-UTXO auto-attached.

**Review pass (8 findings, 7 fixed, 1 deferred):**
- ManagedNode: close() no longer blocks on the start poll (lock held only for
  spawn; poll observes a `closing` flag) — unit-tested; HttpClient closed;
  N2N port re-picked to differ from REST; safe exitValue.
- Native-asset + delegation + withdrawal drafts now show real ADA impact:
  recipientLovelace from the signed tx (min-ADA), the refundable ~2 ₳ stake
  deposit on first delegation, and the withdrawn reward amount.
- Reconnect locks the wallet (stale-session-after-reconnect); `active` is
  volatile; CLI pre-seed connect wrapped so a failure opens the Connect screen
  instead of crashing before the UI.
- Managed start timeout is network-aware (90s devnet, 45min real networks) to
  cover a one-time wallet-index backfill on a seeded chainstate.
- **Deferred (M4)**: orphan managed node on SIGKILL/crash — the shutdown hook
  doesn't run, leaving a node holding the chainstate lock; next launch fails
  with a now-clear "chainstate may be locked" message. Proper fix (PID-file /
  parent-death watchdog / lock-detection-and-reap) belongs with the M4
  packaged app.

**Managed real-network validation (DONE):** using the user-provided synced
preprod chainstate (31G, copied — original untouched, copy reclaimed after),
a preprod node launched with profile `preprod,wallet` (magic 1), resumed from
tip block 4.9M, and ran the M2 per-family-cursor backfill to build the
address-tx/reward index from block 0 (the seeded chainstate lacked it) —
~25min one-time, completed cleanly to UTXO lag 0. Confirmed real preprod data
served: total supply 31.9M ADA, treasury 1.9M, reserves 13M, active stake,
DReps, epoch 300. The **wallet UI connected to it (external mode)** and showed
`preprod · synced · block 4927884` with a working History screen. Validates:
managed mode applies the wallet profile on real networks, the M2 index rebuild
works at mainnet scale, and the full UI works against a real synced node.

## M3 — Desktop UI (2026-07-12, DONE: implemented + reviewed + fixed + verified)

**Review pass (2 correctness/threading finder agents → 7 findings, all fixed):**
- Confirmation polling moved OFF the shared single-thread backend executor
  onto a dedicated daemon thread (`WalletService.trackConfirmation`) — fixes
  both the up-to-120s UI freeze after every send AND the key-retention
  (the poll no longer captures the unlocked Session; it uses only the node
  port + pending store, so it's safe to keep running after lock).
- Confirmation now records the real slot + block hash (node's
  `/txs/{hash}/status` already returned them; the client was dropping them),
  so rollback re-detection (`confirmedAfter`) works.
- `history()` makes node history authoritative and shows a local pending row
  only when its hash is NOT already confirmed on-chain (fixes "confirmed tx
  stuck as pending forever"); load-more uses `>= PAGE_SIZE`.
- `submit()` splits transport failure (RetryableSubmitException — keeps the
  signed draft, no pending record) from node rejection (terminal, FAILED);
  confirmDraft removes the cached draft only on a non-retryable outcome.
- Positive-amount guard in draftSend; auto-unlock passphrase static ref
  dropped after single use.
- Verified clean by the reviewers: FX-thread safety (all backend on the
  executor, all scene mutation via Ui.onFx/Platform.runLater), ADA formatting
  (toPlainString, no scientific notation), locale-neutral BigDecimal.

**Reward-history live verification (monitor):** first devnet reward rows
landed at epoch 1 — genesis staker earned a `member` reward with pool_id via
`GET /accounts/{stake}/rewards`, confirming the M2 reward-history hook works
end-to-end on a running node (the earlier empty result was the wiring-order
bug, now fixed).

### M3 detail (as landed)

**Landed:**
- **WalletService facade** (wallet-core): the ONE production money path —
  unlock → draft → submit → pending-record → confirm — behind a `Session`.
  Probe, UI, and (future) CIP-30 bridge all go through it, so behavior can't
  drift (closes the M1 altitude finding). Adds `draftDelegation` (auto
  stake-registration) and `draftWithdrawal`. Stake txs sign via account-0
  `Account` (hdwallet signer discovery is UTXO-driven; cert-only txs found no
  signer → "No signers found").
- **Ports** (wallet-core `NodeStatusPort`/`HistoryPort`, impl
  `YanoNodePorts` in wallet-node-client): status, tx-status, account info,
  account/address tx history, rewards over the M2 REST endpoints.
- **wallet-ui** (JavaFX 25, MVVM, NO FXML): `WalletUiController` async
  contract (immutable records only — no CCL/node types cross it); screens
  Onboarding (create w/ mnemonic-backup, restore, unlock), Dashboard,
  Send (draft→approval→submit), Receive, History, Staking (delegate/withdraw
  + reward history), Settings; `Shell` with icon nav + live 5s status pill +
  toast overlay; dark theme CSS. Inline SVG icons (native-image friendly).
- **wallet-app**: `DefaultWalletUiController` adapts the contract onto
  `WalletService` on a single-thread backend executor (FX thread never
  blocks; every result hops back via `Platform.runLater`). `YanoWalletApp`
  main + a headless screenshot/auto-unlock/auto-send verification harness
  (PNG via manual ARGB copy — no javafx-swing).
- Probe refactored onto `WalletService` (one money path); gained
  delegate/withdraw commands.

**E2E evidence (devnet, live node)**: UI launched against the running node —
onboarding lists Alice/Bob, dashboard shows real balance (₳894.6) + confirmed
activity, staking shows live delegation ("Delegated to pool1…") + withdrawable
rewards, history renders confirmed txs. Send driven through the controller's
own draft→confirm path headlessly (`AUTO_SEND_SUBMITTED`), delegation
confirmed on-chain through `WalletService` (probe). 4 screenshots captured.

**Learnings:**
1. Reward-history hook must be wired AFTER `accountHistorySubsystem.initialize`
   (the calculator is created earlier in `wireDefaultAccountStateStore`, when
   the history store doesn't exist yet) — first wiring attempt silently no-op'd
   (log line absent); moved to run right after the store is built.
2. Stake/withdraw signing needs the concrete account signer, not the hdwallet
   wrapper, for cert-only transactions.
3. JavaFX 25 + jpackage path holds: no GraalVM native attempted for the UI
   (per ADR-033), node stays the native binary.

**Deferred (M3 follow-ups / M4+):**
- jpackage installers bundling the native node (M4).
- Reward-history full devnet e2e needs several epoch boundaries to accrue —
  monitoring in progress; unit tests already cover the row read/rollback.
- Multi-account switcher, asset metadata display, address-book, auto-lock
  timer, OS-keychain convenience unlock.

## M3 fix — live activity auto-refresh (2026-07-13)

**Symptom (user):** on the Dashboard "Recent Activities", a sent tx stays
`pending` long after it is confirmed on-chain (visible in cardanoscan), then
eventually flips.

**Root cause:** screens are cached (`Shell.navigate` → `computeIfAbsent`) and
`refresh()` runs only on navigation. The 5 s `statusPoller` called *only*
`pollStatus()` (sidebar sync pill), never the active screen. So while sitting on
the Dashboard the activity list is never re-queried — it flips only when you
navigate away and back. The node data is ready fast: `GET /txs/{h}/status` reads
the UTXO index (`getOutputsByTxHash`) and history reads the account-history
index — both updated on block application, ~1 block after inclusion. The lag was
purely UI-side.

**Fix:** added `Screen.poll()` (default no-op) — a *silent* periodic refresh
that keeps last-good data on transient error (no toast). Shell's poller now runs
`tick()` = `pollStatus()` + `activeScreen.poll()` every 5 s. `DashboardScreen`
implements `poll()` via a shared `load(boolean silent)`; `refresh()` = loud,
`poll()` = silent. Pending → confirmed now flips within ≤5 s of on-chain
inclusion without navigating.

Scoped to the Dashboard on purpose: auto-refreshing the full `HistoryScreen`
every 5 s would reset scroll position and collapse loaded pages. History still
refreshes on navigation; a manual refresh affordance there is a possible M4
follow-up.
