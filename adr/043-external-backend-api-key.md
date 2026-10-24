# ADR-043: An API Key for External Backends (and Hosted Blockfrost)

## Status

Proposed (plan only — not yet implemented). **BFH-M0 is done**: probed against
live preprod Blockfrost on 2026-08-13, results in *What the probe found*. Amends
**ADR-038 §3**, whose refuse-mainnet rule was written for yaci-store and reaches
hosted Blockfrost by accident; the probe settles that amendment (§5).

## Date

2026-08-14 (probe results dated 2026-08-13)

## Context

The Connect screen offers two ways to reach a chain: a managed local node, or an
external node URL. The external field is a bare URL — the wallet can send no
credential with it. That excludes three real backends:

- **hosted Blockfrost**, which needs a `project_id` header;
- **a team's shared Yano node behind a reverse proxy or API gateway**, which the
  wallet cannot reach today for want of one header;
- **any managed BF-compatible provider** (Demeter and similar).

### The money path is already Blockfrost

`YanoNodeBackend.connect()` builds every money path on CCL's Blockfrost client
and passes a placeholder token:

```java
// Yano ignores the Blockfrost project_id header; pass a placeholder.
BackendService backendService = new BFBackendService(normalized, "yano");
```

ADR-038 established this ("the wallet is already Blockfrost-shaped, not
Yano-proprietary") and verified by decompiling CCL that `BFUtxoService`,
`BFEpochService` and `BFTransactionService` never call `/genesis`. So UTxOs,
protocol parameters and submission need **no new client** — only a real token in
place of `"yano"`.

### Framing: this is authentication, not a Blockfrost mode

The same one optional field serves all three backends above. Calling the feature
"Blockfrost support" would make it a product-direction change — a light-wallet
mode competing with Eternl and Lace precisely where ADR-041 says not to compete,
and directly against the tagline this wallet shows on its own onboarding screen
(*Your keys. Your node. Nothing in between.*).

Calling it what it is — **external backends may need a credential** — keeps it a
small generalisation of a field that already exists. Hosted Blockfrost is then
one thing a user can point it at, not the feature's identity.

That framing does **not** license pretending users won't point it at mainnet
Blockfrost. They will, and it is a legitimate thing to want. The mainnet rule
(§5) and the privacy disclosure (§7) are designed for that as the primary case.

### Verified by reading this repo

- the `BFBackendService(url, "yano")` construction above;
- `WalletNetwork.blockfrostFlavor()` is `this == YACI_DEVKIT` — flavor is derived
  from the network, and it gates both path selection and the mainnet rule;
- `YanoNodeClient` sends no authentication header on any request;
- **connection is established before any wallet is unlocked** (Connect →
  Onboarding → unlock), so the Argon2id vault is structurally unavailable at the
  moment a backend credential is needed.

### What the probe found (live preprod Blockfrost, 2026-08-13)

Note first that **an unauthenticated probe proves nothing**: Blockfrost checks
the token before routing, so every path — real or invented — answers
`403 "Missing project token"`. These results come from a real preprod key.

| Wallet need | Endpoint | Result |
|---|---|---|
| **Network identity** | `/genesis` | ✅ `network_magic: 1`, `system_start: 1654041600`, `epoch_length: 432000` — the magic gate runs unchanged |
| UTxOs / params / submit | CCL `BFBackendService` | ✅ the client the wallet already uses |
| Protocol params | `/epochs/latest/parameters` | ✅ |
| Sync pill | `/blocks/latest` | ✅ `height`, `hash`, `slot`, `epoch`, `epoch_slot`, `tx_count` |
| Account info | `/accounts/{stake}` | ✅ `registered`, `withdrawable_amount`, `pool_id`, `drep_id` — but **no `drep_type`**, which `AccountView` carries |
| Input resolution (ADR-042) | `/txs/{hash}/utxos` | ✅ inputs with address + amounts |
| Script evaluation | `/utils/txs/evaluate` | ✅ POST, answers in **Ogmios `jsonwsp`** shape — the same family as Yano's endpoint |
| Proposals | `/governance/proposals` | ✅ |
| DRep info | `/governance/dreps/{id}` | ✅ with `active` / `retired` / `expired` / `active_epoch` — **Yano's path and Yano's payload**, not yaci-store's |
| Yano status | `/status` | ❌ **400** `"Invalid path"` — note: *not* 404 |

Two results reshape the design:

**Hosted Blockfrost is not "yaci-store with a token".** On DRep info it matches
**Yano** — same path, same flags — while lacking `/status` like yaci-store. A
boolean flavor cannot express that, which settles §2's three-value enum on
evidence rather than taste. It also means ADR-038's flavor probe needs care:
that probe reads a **404** on `/status` as "not Yano", and hosted Blockfrost
answers **400**. A probe testing for 404 specifically would misclassify it.

**The network scoping is enforced by the server.** The preprod key against the
mainnet host returns:

```
403 {"error":"Forbidden","message":"Network token mismatch.
     Are you using token for the correct network? ..."}
```

So a preprod credential *cannot* read mainnet, whatever the wallet believes.
This also confirms the `preprod…` / `mainnet…` prefix convention §4 relies on.

## Decision

### 1. An optional credential on the external connection

Add an optional API key to the external connection config, threaded to **both**
clients that talk to a backend:

- `BFBackendService(url, apiKey)` — CCL sets the `project_id` header itself;
- `YanoNodeClient` — a new optional header for the wallet's own calls (status,
  history, rewards, governance, simulation).

Empty means what it means today: no header, for a plain Yano node or a DevKit.

### 2. Flavor moves off `WalletNetwork`

This is the structural change, and it sits on a safety gate.

`blockfrostFlavor()` works today only because yaci-store owns its own network
entry, making network and flavor the same fact. Hosted Blockfrost breaks that
identity: **preprod may now be a managed Yano node or Blockfrost**, and the
flavor decides which paths exist (`/status` vs `/blocks/latest`,
`/governance/dreps/{id}` vs `/governance-state/dreps/{id}`) *and* whether
`verifyNetwork` demands proof of magic.

Introduce `BackendFlavor { YANO, YACI_STORE, BLOCKFROST_HOSTED }` carried on the
connection config, and key both path selection and the mainnet rule on it.
`WalletNetwork.blockfrostFlavor()` is deleted rather than left as a second
source of truth.

Done as a pure refactor with no new behaviour, tests pinning the mainnet rule
first — it is the highest-risk step in this ADR, because a wrong flavor is a
wrong answer to "may this wallet talk to mainnet".

### 3. Where the credential lives

Not in the vault: *Verified by reading this repo* records that connecting
precedes unlocking, so the vault is locked at the moment the key is needed. That
is an ordering property of the app, not an implementation gap.

Store it in `connection.json` beside the URL it belongs to, with owner-only file
permissions, and **redact it everywhere it could be logged** — connect log
lines, error messages, and the node-log viewer. It is a read/submit credential,
not a spending key; the vault protects keys that move funds, and stretching it
to cover a service token would mean a locked wallet could not connect at all.

OS-keychain storage is a later hardening, recorded here so the tradeoff is not
rediscovered: it removes plaintext at rest but adds a platform-specific
dependency to a path that must work on all three.

### 4. Paste the key, configure the connection

A Blockfrost project ID is network-scoped and carries its network as a prefix
(`preprod…`, `preview…`, `mainnet…`). Use it:

- paste a key → select that network and fill
  `https://cardano-<network>.blockfrost.io/api/v0` (note **v0**, not the `v1`
  the other backends use);
- if the pasted key's network disagrees with the selected network, **refuse**
  with "this key is for mainnet" rather than letting the request fail obscurely.

One paste configures the connection and closes a footgun. The prefix convention
is confirmed (BFH-M0), and the server enforces the same scoping anyway — so a
mismatch the wallet failed to catch still fails safely, with a "Network token
mismatch" from Blockfrost rather than data from the wrong chain.

### 5. The mainnet rule, amended

ADR-038 §3 refuses mainnet for the BLOCKFROST flavor, reasoning that *"real
hosted Blockfrost would be a legitimate mainnet backend, but it cannot prove it
to us today"*. **For hosted Blockfrost that premise is false**, on two counts,
both now measured:

1. it serves `GET /genesis` with `network_magic`, so `verifyNetwork` runs
   unchanged — the same proof Yano gives;
2. the credential is **scoped to one network by the server**. A preprod key
   asking the mainnet host is refused with "Network token mismatch". That is a
   stronger guarantee than any check the wallet performs on itself, because it
   does not depend on the wallet being correct.

The rule therefore becomes principled rather than blanket:

> **A wallet may only connect to mainnet over a backend that proves its network.**
> `YANO` proves it via `/genesis`. `BLOCKFROST_HOSTED` proves it via `/genesis`
> *and* a network-scoped credential. `YACI_STORE` cannot, and stays refused.

ADR-038's blanket refusal was correct for what it could see; it was written
before anyone pointed a real key at the service. This supersedes it for the
hosted flavor only.

### 6. Capabilities stay honest — and the honest answer is uncomfortable

The attractive argument for this feature is that simulate-before-you-sign would
visibly degrade on a hosted backend, letting a user *experience* ADR-041's claim
by contrast instead of reading it. **The probe rules that argument out.** Hosted
Blockfrost serves both halves of ADR-042: `/txs/{hash}/utxos` resolves inputs,
and `/utils/txs/evaluate` evaluates scripts in the same Ogmios shape Yano uses.
Modulo the `resolveOutput` mapping (per-output on Yano, whole-tx on Blockfrost)
and the missing `drep_type`, the flagship feature can run at full fidelity
against a hosted backend.

So the differentiation is **not** capability. Say it plainly rather than build a
UI that implies otherwise:

- **Trust.** On Yano, the effect shown at signing time is computed from a ledger
  this machine validated. On a hosted backend it is computed from what a third
  party says the chain contains. Same screen, different thing being trusted —
  and a compromised or wrong backend produces a confident, wrong effect summary.
- **Privacy.** §7.

That distinction is real but it is not felt, which makes it weaker product
footing than "the feature doesn't work over there" would have been. Two
consequences follow. First, `SimulationCapabilities` must still be per-flavor
and honest — where a shape genuinely differs, degrade rather than guess.
Second, whatever the Connect screen says about hosted backends is the *only*
place this distinction gets made, so it has to say it in those terms, not in
terms of missing features.

The try-it-first funnel still stands on its own: connect in thirty seconds
rather than after a start that can take hours (BACKLOG E20). It simply no longer
comes with a built-in reason to graduate.

### 6b. The three-flavor path map

ADR-038 §4 mapped two flavors; this is the third column, from BFH-M0. It is the
implementation checklist for BFH-M1, and it shows why the split is not a
spectrum — hosted Blockfrost agrees with Yano in one row and with yaci-store in
the next.

| Need | `YANO` | `YACI_STORE` | `BLOCKFROST_HOSTED` |
|---|---|---|---|
| Reachability / sync pill | `/status` (chain + utxo lag) | `/blocks/latest` | `/blocks/latest` — no lag figure |
| Network magic | `/genesis` | ✗ none | `/genesis` ✅ |
| Mainnet allowed | yes | **no** | **yes** (§5) |
| DRep info | `/governance/dreps/{id}` | `/governance-state/dreps/{id}` | `/governance/dreps/{id}` — **as Yano** |
| Proposals | `/governance/proposals` | same | same |
| Account info | `/accounts/{stake}` | same | same, but **no `drep_type`** |
| Input resolution | per-output route | — | `/txs/{hash}/utxos`, pick index |
| Script evaluation | `/utils/txs/evaluate` | same | same (Ogmios `jsonwsp`) |
| Auth | none | none | `project_id` header |

Two implementation notes fall out. `AccountView.drepType` has no source on a
hosted backend, so it is null there and the UI must already tolerate that (it
does for accounts delegating to nobody) — but "delegates to a DRep of unknown
type" and "delegates to nobody" must not render identically. And the flavor
probe cannot test for a 404 on `/status`: hosted Blockfrost answers **400**.

### 6c. What this ADR does not do

- **No light-wallet mode.** No feature is gated behind "hosted or not" beyond
  what the path map forces. The wallet does not grow a second personality.
- **No multi-backend failover or automatic fallback.** The tempting adjacent
  idea — use hosted Blockfrost *while the managed node warms up* (BACKLOG E20),
  then switch when it is ready — is explicitly out. It means two sources of
  truth for the same wallet within one session, and it collides with the same
  constraint E20 already hit: `WalletService.Session` belongs to the service
  that created it, so "switch backends underneath an unlocked wallet" is not a
  configuration change but a re-unlock. If it is ever wanted, it needs its own
  ADR, not a paragraph in this one.
- **No credential sharing between networks.** One key per connection, scoped by
  the server anyway (§5).

### 7. Say what a hosted backend sees

On a hosted backend every address the wallet queries goes to a third party, and
ADR-037 account discovery gap-scans up to 20 accounts × 20 addresses. For a
wallet whose premise is not doing that, this belongs in plain words on the
Connect screen at the moment of choosing — not in a document. The managed option
already carries a hint; this is its counterpart.

### 8. Rate limits: handle reactively, because nothing announces them

Measured: 30 rapid sequential `/blocks/latest` calls all returned 200, and the
responses carry **no `ratelimit-*` or `retry-after` headers**. So the wallet
cannot pace itself proactively — there is no budget to read — and sequential use
is not obviously fragile.

That makes 429 a reactive concern rather than a planned-for budget: ADR-037
discovery gap-scans up to 20 accounts × 20 addresses, which is the one path
likely to find the limit, and this probe did not test it concurrently. Handle a
429 as a first-class state ("rate-limited by the backend, retrying") with
backoff, rather than letting it surface as a failed connection or an empty
account list. Throttle discovery on hosted flavors only if BFH-M4 measures a
real problem — inventing a limit the service does not publish would slow the
common case for a hypothesis.

## Milestones

- **BFH-M0 — probe, then decide. ✅ done 2026-08-13.** Results in *What the probe
  found*: every port the wallet needs exists, `/genesis` carries the magic, the
  credential is network-scoped server-side, and hosted Blockfrost sits on Yano's
  side of the DRep split while lacking `/status`. Still unprobed: `/rewards`
  history shape, and discovery under concurrency (§8).
- **BFH-M1 — flavor decoupling.** `BackendFlavor` on the connection config,
  `verifyNetwork` and path selection keyed on it, `WalletNetwork.blockfrostFlavor()`
  deleted. No new user-visible behaviour; tests pin the mainnet rule for all
  three flavors.
- **BFH-M2 — the credential.** Optional key on the external config, threaded to
  `BFBackendService` and `YanoNodeClient`, persisted, redacted in every log and
  error path. Verified against a Yano node behind an auth proxy — the case with
  no Blockfrost in it at all.
- **BFH-M3 — Connect screen.** Optional field, paste-to-configure, network
  mismatch refusal, the privacy sentence.
- **BFH-M4 — capabilities + limits.** Per-flavor `SimulationCapabilities` with
  degraded states surfaced in the signing prompt; discovery throttling and the
  rate-limited state.
- **BFH-M5 — end to end.** Balances, history, send, and a CIP-30 signature over
  hosted preprod; the same with a Ledger; then the mainnet rule exercised both
  ways (allowed with a verifying backend, refused without).

## Alternatives considered

- **Do nothing.** Defensible, and it keeps ADR-041's line clean. Rejected
  because it also blocks the case with no Blockfrost in it: a team's shared Yano
  node behind an auth gateway is unreachable today for want of one header, and
  that is a full-node deployment this wallet should obviously support.
- **A separate "light mode" build or product.** Keeps the flagship product pure.
  Rejected as disproportionate: the money path is already the Blockfrost client
  (§*The money path is already Blockfrost*), so a fork would duplicate
  everything to express a difference of one HTTP header.
- **Make the user run a BF-compatible proxy that injects the header.** Zero
  wallet change, and it works today. Rejected as a non-answer for the audience
  that wants this — someone unwilling to run a node is not going to run a proxy
  — though it stays the honest workaround until BFH-M2 ships.
- **Put the credential in the Argon2id vault.** Rejected on an ordering fact,
  not a preference: connecting precedes unlocking, so the vault is locked when
  the key is needed (§3).

## Consequences

- One backend and one money path still, as in ADR-038: the credential is a
  header, not a fork. Yano behaviour is unchanged when the field is empty.
- **Flavor becomes a first-class connection property.** This is a real
  maintenance obligation: every new backend-shaped feature must ask which
  flavors have it, and the honest answer sometimes has to reach the UI. ADR-038
  already named this as the ongoing cost of two flavors; this ADR adds a third
  and makes the count independent of the network list.
- The wallet gains a way to be tried in seconds. Whether that is a funnel or an
  exit is now an open question rather than a designed-in answer: §6's probe
  result means a hosted backend runs the flagship feature at full fidelity, so
  nothing in the product pushes a user onward except the trust and privacy
  argument the Connect screen makes in words.
- A plaintext service credential appears in `connection.json`. Acceptable for a
  read/submit token, worth revisiting if the same field is ever asked to hold
  something stronger.
- **The tagline gets a caveat.** *Nothing in between* stays true of the managed
  path and becomes a claim about a mode rather than about the product. Worth
  saying out loud rather than discovering in a review: if this feature ends up
  carrying most usage, the differentiation ADR-041 rests on has eroded, and the
  answer then is a product decision, not a code change.
