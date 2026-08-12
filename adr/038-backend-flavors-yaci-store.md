# ADR-038: Backend Flavors — Yano and yaci-store (Yaci DevKit)

## Status

Implemented. BF-M1 and BF-M2 are built; BF-M3 (devkit end-to-end) is under way —
send, lock and a Plutus unlock have been driven against a live DevKit through the
CIP-30 connector. See *What the live DevKit actually serves* for the two routes
this ADR's path map got wrong.

## Date

2026-07-17

## Context

Yaci DevKit spins up a local devnet with **yaci-store** (its indexer) on
`:8080/api/v1`, but devkit users have **no real wallet** for it. Meanwhile the
Yano wallet is a full wallet with hardware and CIP-30 support. Connecting the two
would give devkit something no other Cardano tool has: spin up a devnet, open the
wallet, and sign a script transaction with a Ledger against your local chain.

### What we found (probed against a live devkit devnet, 2026-07-17)

**The wallet is already Blockfrost-shaped, not Yano-proprietary.** `YanoNodeBackend`
builds every money path on CCL's `BFBackendService`:

```java
BackendService backendService = new BFBackendService(normalized, "yano");
// Yano ignores the Blockfrost project_id header; pass a placeholder.
```

and devkit documents yaci-store as *"Blockfrost-compatible … can be used in a Java
app with Cardano Client Lib's Blockfrost backend"* — the same client. Verified by
decompiling the CCL jar: **`BFUtxoService`, `BFEpochService` and
`BFTransactionService` never call `/genesis`**, so the money paths need no changes.

Of yaci-store's **119** endpoints, everything the wallet's money path needs is
present and working:

| Wallet need | yaci-store | Status |
|---|---|---|
| UTxOs | `/api/v1/addresses/{address}/utxos` | ✅ |
| `isUsedAddress` (gap scan, ADR-037 discovery) | `/api/v1/addresses/{address}/transactions` | ✅ |
| Protocol params | `/api/v1/epochs/latest/parameters` | ✅ |
| Submit | `/api/v1/tx/submit` | ✅ |
| Tx status | `/api/v1/txs/{txHash}` | ✅ |
| Account info (delegation, rewards) | `/api/v1/accounts/{stakeAddress}` (+ `/rewards`) | ✅ |
| Active proposals | `/api/v1/governance/proposals?status=active` | ✅ (filter accepted) |
| DRep info | `/api/v1/governance-state/dreps/{drepId}` | ⚠️ different path |
| Sync status | — no `/status` | ❌ use `/blocks/latest` |
| **Network identity** | — **no `/genesis`, no magic anywhere** | ❌ **the real problem** |

**Bonus:** yaci-store has `/api/v1/utils/txs/evaluate` — script evaluation, the
exact capability Koios refused us on the CIP-30 Plutus test (ADR-035). A
devkit-connected wallet can evaluate redeemers locally. yaci-store is also richer
than Yano's wallet API in places (adapot, epoch stake, pool history) — future
features, not blockers.

### The problem: nothing proves which network yaci-store serves

The wallet's safety gate is:

```java
// A wallet must never talk to a node on a different network than its stored wallets.
long actualMagic = getGenesis().networkMagic();
```

No yaci-store endpoint carries the protocol magic. (`/api/v1/network` is
Blockfrost's *supply* endpoint — supply and stake totals, no magic.) The gate
cannot run as written, and deleting it is not acceptable: it is what stops a
wallet from mistaking mainnet for a devnet and treating real ADA as test ADA.

## Decision

Add a **backend flavor probe** — a capability model, not a second backend. The
existing split (`BFBackendService` for money + `YanoNodeClient` for extras) is
already the right shape; the flavor decides which extras exist.

### 1. Flavor detection

Probe `GET /status` at connect time (verified live: Yano answers with
`chain`/`utxo`; yaci-store returns **404**):

- **`YANO`** — full features, strict magic verification, exactly as today.
- **`BLOCKFROST`** — yaci-store (or real Blockfrost): degrade per the map below.

### 2. Network identity — an explicit "Yaci DevKit" choice

**Primary mechanism: let the user declare it.** Add **Yaci DevKit** to the
network dropdown (`WalletNetwork.YACI_DEVKIT`, magic **42** — devkit's default,
default URL `http://localhost:8080/api/v1`). Choosing it asserts both facts the
wallet cannot discover: *this is a devnet* and *this backend is yaci-store*.

**Why this is safe — and stronger than a heuristic.** Protocol magic is not part
of a Cardano address; only the **network id** is. Probed from our own
`WalletNetwork` values with one seed:

```
devnet   magic=42         netId=0  addr=addr_test1qq8ac7qq…  ┐
preview  magic=2          netId=0  addr=addr_test1qq8ac7qq…  ├─ IDENTICAL
preprod  magic=1          netId=0  addr=addr_test1qq8ac7qq…  ┘
mainnet  magic=764824073  netId=1  addr=addr1qy8ac7qq…       ← different
```

So a wallet in any devnet/testnet mode derives `addr_test…` keys, which **cannot
address mainnet funds**. The catastrophic case — mistaking real ADA for test ADA
— is structurally impossible under an explicit non-mainnet choice, because the
choice changes *what the wallet derives*, not merely what it believes. That is a
stronger guarantee than any endpoint sniffing gives us.

**The residual risk is testnet↔testnet, and it is minor.** Because preprod,
preview and devnet share addresses, a wallet in Yaci DevKit mode pointed at a
*preprod* backend would show that seed's real preprod funds and could spend them.
Those are worthless test ADA, so this is a correctness/UX bug, not a safety one.
Wallets are stored per network directory, so the profiles stay separate.

### 2b. Network fingerprint — optional, deferred

⚠️ **A heuristic, not proof.** Kept in this ADR as an *optional* nicety for the
testnet↔testnet mismatch above (warn "this URL doesn't look like the network you
picked"). **Not required for BF-M1/BF-M2** — the explicit choice in §2 carries the
safety property. Implement only if the mismatch proves annoying in practice, and
delete it once yaci-store exposes magic.

`GET /api/v1/epochs/latest` returns `epoch`, `start_time`, `end_time`, from which:

```
epochLength         = end_time - start_time
impliedSystemStart  = start_time - epoch * epochLength
```

Match `impliedSystemStart` against the known genesis times:

| Network | System start | Epoch length |
|---|---|---|
| mainnet | 1506203091 | 432000 |
| preprod | 1654041600 | 432000 |
| preview | 1666656000 | 86400 |
| devnet | anything else | anything else |

**Verified against the live devkit devnet:** epoch length `600s`, implied system
start `1784292534` → matches no known network → correctly identified as devnet.
The fingerprint is **stable across epochs** — probed at epoch 3 and again at
epoch 5 (the devnet produces blocks fast), both computed the identical
`1784292534`. Both values come from genesis math, not observed blocks (this
devnet reported `block_count: 0`, `first_block_time: 0`).

### 3. The mainnet rule (the safety property)

**A wallet may only connect to mainnet over a backend that proves its network.**

- `YANO` flavor → magic verified via `/genesis` → mainnet allowed, as today.
- `BLOCKFROST` flavor + mainnet → **refuse the connection** with a plain message.
  (Real hosted Blockfrost would be a legitimate mainnet backend, but it cannot
  prove it to us today; supporting it is not a goal, and §2's derivation
  guarantee only covers the *non*-mainnet direction.)
- `BLOCKFROST` flavor + devnet/preprod/preview → allowed. The user's explicit
  network choice governs derivation, so mainnet funds are unreachable.

Together with §2 this keeps the guarantee where it actually matters: real ADA is
never reachable from a wallet whose backend cannot say what chain it is.

### 4. Path/capability map for the BLOCKFROST flavor

| Feature | Change |
|---|---|
| Sync pill | `/blocks/latest` (`number`, `slot`, `hash`, `epoch`) instead of `/status`; no lag figure, so show "connected · block N" rather than a sync/lag state |
| `isReachable()` | `/blocks/latest` (or `/actuator/health`, which yaci-store answers `{"status":"UP"}`) |
| DRep info | `/governance-state/dreps/{drepId}` |
| Proposals | `/governance/proposals?status=active` — unchanged |
| Script evaluation | *(new capability)* `/utils/txs/evaluate` — available here, absent on Yano |

### 5. The dropdown entry

`WalletNetwork.YACI_DEVKIT("yaci-devkit", magic 42)` joins the existing
ConnectScreen picker (which already offers devnet/preview/preprod/mainnet with a
managed/external URL field), defaulting the URL to
`http://localhost:8080/api/v1`. It is the same chain shape as `DEVNET`
(`Network(0, 42)`) but a distinct entry because it additionally declares the
**backend flavor**, and because devkit chains are wiped often — keeping those
throwaway wallets in their own storage directory, apart from a hand-run devnet's,
is a feature.

This conflates "network" and "backend" in one dropdown, which is a deliberate
trade: it matches how users think ("I'm on devkit") and carries both facts the
wallet cannot otherwise learn. Once yaci-store exposes magic, the cleaner
`network × flavor` split becomes possible and this entry can become a plain
connection preset.

## Replace this workaround

**The fingerprint is a heuristic and should not outlive the gap that forced it.**
yaci-store is a bloxbean project — the right fix is upstream: expose the protocol
magic (a `/api/v1/genesis`, or a `network_magic` field on `/api/v1/network`).
Then `verifyNetwork` works unchanged for both flavors, the fingerprint code and
the "unverified network" notice are deleted, and the mainnet rule becomes a plain
magic comparison.

Tracked upstream: **bloxbean/yaci-store#1018** (filed generically — no mention of this wallet)
(backlog E16). With the explicit dropdown choice in §2 carrying the safety
property, this is now an accuracy/robustness ask rather than a blocker — it also
removes the hardcoded assumption that every devnet uses magic 42.
Until then the fingerprint's limits are:

- It infers identity from genesis arithmetic, not from a chain-verifiable fact.
- A devnet deliberately configured with mainnet's system start and epoch length
  would fingerprint as mainnet (contrived, but it is not a proof).
- DevKit's epoch-shifting / time-travel features (devkit ADR-0008) move genesis
  intentionally — such a chain fingerprints as "devnet", which is correct, but
  means the check can never be tightened into an equality assertion for devnets.

## What the live DevKit actually serves

Probed 2026-08-12 against a running Yaci DevKit via its OpenAPI document
(`/v3/api-docs`, 142 routes). Two routes this ADR assumed are **absent**, and
both failed *silently* — nothing errored, the wallet simply said something
untrue:

| Wallet needed | yaci-store | Now used |
|---|---|---|
| `/accounts/{stake}/transactions` | **absent** | `/addresses/{address}/transactions` |
| `/governance/dreps/{drepId}` | **absent** | `/governance-state/dreps/{drepId}` |

The history route 404s, which rendered an empty list as though the wallet had
never transacted. The DRep route 404s, which the wallet reads as "not
registered" — so it would offer to register a DRep that already exists.

The DRep payload also differs in shape, not only in path: yaci-store's
`DRepDetailsDto` carries a single `status` string and a `registration_slot`,
where a Yano node carries `active`/`retired`/`expired` and `registered_epoch`.
`registeredEpoch` is therefore 0 on this flavor and must be read as *unknown*.

Because the flavor decides which routes **exist**, it is fixed at connect time
from the user's network choice rather than probed per call — `YanoNodeClient`
carries it, and `HistoryPort.walletTransactions` takes both the stake and
payment address so each backend can be asked the question it can answer.

One consequence worth stating: on this flavor, history is per-address rather
than per-account. For a wallet that uses one payment address per account they
are the same set; for a future multi-address wallet they would not be.

## Milestones

- **BF-M1 — flavor probe + degradation.** `NodeFlavor` detection, `NodeStatusPort`
  implementation for the BLOCKFROST flavor (pill via `/blocks/latest`, DRep path
  map), features that have no endpoint hide rather than error. Tests against
  recorded yaci-store responses.
- **BF-M2 — Yaci DevKit entry + mainnet rule.** `WalletNetwork.YACI_DEVKIT`
  (magic 42, default URL, own storage dir), the refuse-mainnet-over-BF rule.
  The fingerprint (§2b) is explicitly **not** in scope.
- **BF-M3 — devkit end-to-end.** Connect to a live devkit devnet: balances, send,
  history; then a script tx (using `/utils/txs/evaluate`), and with a Ledger.

## Consequences

- One backend, one money path: no fork of the client, and Yano behaviour is
  unchanged (the probe adds one request at connect time).
- Yano keeps a real advantage — it can prove its network, so it is the only
  flavor allowed on mainnet until yaci-store exposes magic.
- Governance depth differs by flavor; the UI must degrade from capabilities, not
  assume Yano. This is the main ongoing maintenance cost of supporting two
  flavors, and the reason to model it as capabilities rather than an
  `if (isYaciStore)` sprinkled through screens.
- Real Blockfrost (the hosted service) also matches the BLOCKFROST flavor. That
  is not a goal, but it falls out — with the same mainnet rule applying, since
  hosted Blockfrost would fingerprint mainnet correctly.
