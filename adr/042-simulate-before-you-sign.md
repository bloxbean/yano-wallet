# ADR-042: Simulate Before You Sign — Show What a Transaction Actually Does

## Status

Implemented (SIM-M0 … SIM-M5, 2026-08-10). See *Implementation notes* for the
three places the build departed from this plan and why.

## Date

2026-08-09 (amended 2026-08-10 — source-verified against the pinned node
release; seam, `signData` and module placement corrected; SIM-M0 added)

## Context

When a dApp calls `signTx`, the wallet today asks:

```java
/** Ask the user to approve a signing/submit request from {@code origin}. */
boolean confirmSign(String origin, String summary);
```

`summary` is a string. The user sees an origin, some text, and two buttons. This
is how every Cardano wallet works, and it is the single largest safety gap in the
ecosystem: the user is asked to authorise a transaction whose effects nobody has
shown them. "Blind signing" drains are not exotic attacks — they are the normal
outcome of an interface that cannot answer *what will this do to my wallet?*

The transaction is fully determined at this point. Its inputs, outputs, mint
field, certificates, withdrawals and scripts are all in the CBOR. Nothing about
the effect is unknowable; it is simply not computed.

A light wallet cannot compute it without help. Resolving the transaction's inputs
means looking up arbitrary UTxOs — including the dApp's own — and running the
Plutus scripts. Delegating that to a remote simulator moves the trust rather than
removing it: the user is then trusting the simulator not to lie about what they
are signing, which is the same problem one level up.

**We have a validated copy of the chain and a script evaluator in-process.** This
is the clearest case in ADR-041's filter of something we can do and they cannot.

### What the node already provides (verified 2026-08-09)

- `POST /utils/txs/evaluate` (`EvaluationResource`) — Ogmios/Blockfrost-shaped,
  accepts raw CBOR (`application/cbor`) or hex (`text/plain`). Returns ExUnits
  per redeemer as `{"EvaluationResult": {"spend:0": {"memory":…, "steps":…}}}`,
  or `{"EvaluationFailure": {"message": …}}`.
- `GET /utxos/{txHash}/{index}` (`UtxoResource`) — resolves an output reference
  to its address, value and datum. This is what makes input resolution possible.

Both resources are present in the pinned `yano-0.1.0-pre12` release jar — the
History failure of 2026-08-07 (calling endpoints no published build served)
does not repeat here. Script evaluation was confirmed live on the wallet's
preprod node ("Script evaluator set for /utils/txs/evaluate endpoint" in
`node.log`) — though that node was a locally built jar (`.yano-node` link);
the pre12 release itself was verified *statically*: both resource classes and
the `tx-evaluation: true` default are in the jar, and evaluation does not
depend on block production. SIM-M0's runtime probe closes the remaining gap.
The managed node applies no UTxO filter, so foreign (dApp-owned) inputs
resolve the same way ours do.

Three limits matter for the design:

1. **Evaluation returns only ExUnits.** It answers *"do the scripts succeed, and
   at what cost"* — not *"what moves"*. The value diff is wallet-side work.
2. **Evaluation may be unavailable.** `isTransactionEvaluationAvailable()` is
   false unless tx-evaluation is enabled and protocol parameters are configured;
   the endpoint then returns an `EvaluationFailure`, not an HTTP error.
3. **Evaluation resolves inputs from the node's own UTxO state.** A transaction
   spending an output that is not yet on-chain (a chained dApp tx) comes back as
   `EvaluationFailure` even when its scripts are fine. That must surface as
   "could not verify", never as "your scripts will fail" — the two mean opposite
   things, and only one is a reason not to sign. It is the same degraded state
   the value diff enters on an unresolvable input; both share the single
   "cannot fully determine" outcome.

Client semantics to encode in `YanoNodeClient`: an `EvaluationFailure` arrives
as HTTP 200 (parse the body, never the status); a miss on
`/utxos/{txHash}/{index}` is a 404 with an empty body; and CCL's
`BFUtxoService.getTxOutput` resolves through `/txs/{hash}/utxos` — historical
on-chain outputs, a different question from the node's unspent-only
`/utxos/{txHash}/{index}` — so the client gets a direct `getUtxo(txHash, index)`
rather than reusing CCL's.

## Decision

Compute a **transaction effect summary** before the approval prompt, and show it
in place of today's free-text summary. The summary answers three questions in
this order:

1. **What leaves this wallet?** (ADA and each asset, net)
2. **What arrives?**
3. **What else happens?** (mint/burn, certificates, withdrawals, script outcome,
   collateral at risk, validity window)

### The value diff

The effect on *this wallet* is a set difference, computed from the decoded body:

```
mine        = inputs whose resolved address belongs to this account
theirs      = the rest
outputsMine = outputs whose address belongs to this account

spent    = Σ value(mine)
returned = Σ value(outputsMine)
net      = returned − spent           # per asset, ADA included
fee      = body.fee
```

`net` is what the user actually cares about, per asset. Everything else is
context. Assets are shown by name where a registry or on-chain metadata is
available and by `policy.assetName` otherwise — **never** silently omitted, since
an unnamed asset leaving the wallet is exactly the case an attacker wants
invisible.

Inputs are resolved via `GET /utxos/{txHash}/{index}`. Inputs the node cannot
resolve (already spent, or not yet on-chain) are surfaced as *unresolved*, and an
unresolved input that could be ours degrades the summary to "cannot fully
determine" rather than under-reporting the loss. **A partial answer presented as
complete is worse than no answer.**

### Risk signals

Derived facts, each with a plain-language reason. These are heuristics and must
be labelled as such — they inform, they do not authorise:

| Signal | Condition |
|---|---|
| Asset leaving | any non-ADA `net < 0` |
| Unexpected mint/burn | non-empty mint field |
| Collateral at risk | collateral inputs present, with the amount |
| Unknown script | output to a script address not seen in this wallet's history |
| Total-value drain | `net(ADA)` ≈ −(entire balance) |
| Datum-bearing output | output carries a datum the wallet cannot interpret |
| Script failure | `EvaluationFailure` — the transaction will fail on-chain |
| Certificates | stake key deregistration, DRep change, pool delegation change |
| Withdrawal | reward withdrawal, with destination |
| Expired / far-future validity | `ttl` already past or implausibly distant |

`EvaluationFailure` deserves emphasis: a transaction whose scripts fail wastes
the fee and burns collateral. Today the user finds out afterwards. We can say so
before signing.

### Script evaluation goes through the endpoint, not in-process

The evaluator the node uses (`scalus-bridge`) is published code the wallet
could link directly; rejected. `TransactionEvaluator.evaluate(txCbor,
inputUtxos)` takes *pre-resolved* inputs, so the wallet would rebuild input
resolution over REST anyway — and any input it missed would silently change
the answer. The node wires the evaluator to state the wallet does not have:
the validated `UtxoState` (inputs, reference inputs, reference scripts), the
*effective* per-epoch protocol parameters from ledger state (cost-model drift
between two suppliers is two different ExUnits answers), and slot config
resolved from the node's genesis. Reproducing those wallet-side is exactly the
"confident wrong answer" this ADR treats as a security defect. Linking it
would also pull Scala 3 and native `blst` into the desktop bundle and break
the rule that the wallet depends on a Yano *distribution*, never Yano
libraries (`gradle/yano-node.gradle`). One evaluator, one source of truth.

### Contract change

Today's `summary` string is threaded through three contracts, not one: the
connector's `Cip30Approvals.confirmSign(origin, summary)` — the consent gate
actually fires in `Cip30Dispatcher.signTx`, one module before
`WalletCip30Wallet` is reached — then the app's `Cip30ApprovalGate`, then the
UI's `Cip30Prompt.confirmSign(origin, summary)`, plus the test fakes behind
each. Module boundaries dictate where each piece of the change lives:

- `wallet-connector-host` is deliberately node-free ("pure protocol"), so it
  cannot compute effects. `Cip30Approvals.confirmSign` changes to carry the
  transaction itself — `confirmSign(String origin, String txHex, boolean
  partialSign)` — and stops summarising.
- The effect engine lives in `wallet-core`, next to the node backend and the
  account's addresses. `Cip30ApprovalGate` (wallet-app) invokes it and hands
  the result to the prompt.
- `Cip30Prompt.confirmSign(String origin, TxEffectView effect)`, with
  `TxEffectView` defined beside `Cip30Prompt` in the UI contract package as a
  record of plain types only — strings, longs, booleans and lists of records —
  per the ADR-033 boundary rule.

`signData` shares `confirmSign` today ("Sign data with …"). It is not a
transaction and has no effect to summarise, so it gets its own
`confirmSignData(origin, address)` rather than a `TxEffectView` it cannot
fill.

`TxEffectView` carries the per-asset diff, the risk signals, the script
outcome, and a `complete` flag that is false when inputs could not be resolved.
The raw CBOR stays available behind a "details" disclosure so nothing is hidden
by summarising.

### Where it runs

In `Cip30ApprovalGate.confirmSign`, before the prompt is shown — that is, at
the consent gate `Cip30Dispatcher.signTx` calls, one step before
`WalletCip30Wallet.signTx` and the signer are ever reached. It runs on the
bridge worker thread, which is about to block on the user anyway; what it must
never do is block unboundedly on the node. The simulation is best-effort with
a **hard timeout** (proposed: 3s): a slow or unavailable node must produce a
degraded prompt ("could not verify — proceed with caution"), never a hang. The prompt is a
blocking modal on a bridge thread; making it depend on an unbounded node call
would turn a node hiccup into a frozen wallet.

Hardware signing keeps its existing device-side confirmation and tx-hash gate
(ADR-034). This is an additional check, not a replacement — the device still
shows what it will sign.

### Where else it applies

The same effect summary should back the wallet's own Send/Staking/Governance
confirmations via `ApprovalOverlay`, not just dApp requests. A user benefits from
"what will this do" whether the transaction came from a dApp or from our own
form, and one implementation serving both keeps them honest with each other.

## Milestones

- **SIM-M0 — node capability gate.** ADR-041's declared-minimum-node
  prerequisite, built first: probe once at connect, record whether
  `/utxos/{hash}/{index}` and `/utils/txs/evaluate` exist, and degrade legibly
  where they do not. Probe capabilities, not version strings: `GET /node/config`
  reports the build-time `quarkus.application.version`, and a locally built
  node can be newer than the pinned release while reporting an older version
  (observed: a post-pre12 build reporting `0.1.0-pre11`). The version string is
  for the error message, not the decision.
- **SIM-M1 — value diff, no scripts.** Decode the body, resolve inputs, compute
  the per-asset diff, render it in the CIP-30 prompt. Covers the majority of real
  transactions (simple sends, most dApp interactions) and is testable entirely
  from fixtures. Ship this alone; it already beats a free-text summary.
- **SIM-M2 — script evaluation.** Call `/utils/txs/evaluate`, show success/failure
  and ExUnits, surface `EvaluationFailure` prominently. Handle unavailable
  evaluation as a degraded state rather than an error, and route the
  unconfirmed-input failure (limit 3 above) to the shared "cannot fully
  determine" state — distinct from a real script failure.
- **SIM-M3 — risk signals.** The table above, each with its plain-language reason.
- **SIM-M4 — reuse for wallet-built transactions** via `ApprovalOverlay`.
- **SIM-M5 — verification.** Replay real preprod/mainnet transactions with known
  outcomes and assert the computed diff matches what the chain actually did.

## Implementation notes

Three things this plan got wrong, found while building it. All three are the
same mistake in different clothes: **value can enter a transaction without
passing through a UTxO we own, and a diff built only from inputs and outputs
cannot see it.**

1. **Withdrawals are value leaving, not an annotation.** The plan's value diff
   treats a reward withdrawal as context. It is not: value conservation is
   `Σinputs + Σwithdrawals + refunds = Σoutputs + fee + deposits`, so a
   withdrawal from our own reward account is our money entering the transaction,
   and the protocol withdraws the *entire* reward balance. Routed to a stranger's
   output it appears in neither `spent` nor `returned` — a wallet with 10 ₳ of
   rewards would be told a transaction taking all of them "leaves ₳2", while the
   wallet's own signer happily adds the stake-key witness. Our withdrawals are
   now counted on the spent side.
2. **Deposit refunds are the same shape.** A Conway `UnregCert` /
   `UnregDRepCert` refunds its deposit into the transaction — up to 500 ₳ for a
   DRep. Counted when the credential is ours. The legacy `StakeDeregistration`
   carries no amount and cannot be priced without protocol parameters, so it is
   disclosed through the certificate signal instead.
3. **A script verdict needs more than the spending inputs.** The node's evaluator
   resolves spending inputs, collateral *and* reference inputs, and reports any
   it cannot see as an ordinary `EvaluationFailure` — indistinguishable from a
   real script error. A verdict ("they pass" / "they fail") is therefore only
   issued when every outpoint of all three kinds resolved **and** the redeemers
   were actually attached to the CBOR that was evaluated. A partially built
   transaction whose redeemers arrive after signing gets "could not verify",
   never "your scripts fail" and never "they succeed".

Two deliberate departures from the plan's text:

- **"Unknown script" is not history-aware.** The plan defines it as an output to
  a script address *not seen in this wallet's history*. The wallet has no
  script-address history to consult, so it is implemented as an INFO signal on
  any output to a script address ("funds go to a smart contract… getting them
  back depends on that contract"), which is true for every such output and does
  not pretend to a judgement the wallet cannot make.
- **SIM-M5 verifies against the ledger's arithmetic, not against replayed chain
  transactions.** Replay needs a transaction's CBOR, and the pinned node serves
  no `/txs/{hash}/cbor` (nor `/blocks/{hash}/txs`) — verified against a running
  node. Fetching it from a third-party API is exactly the trust dependency
  ADR-041 exists to avoid. Instead, randomised transactions are checked against
  `walletNet + strangersNet = −fee` and per-asset conservation, with the summed
  expectation computed independently of the engine. Mutation-testing confirms
  the suite catches the withdrawal bug above. **Chain replay remains worth doing
  once the node exposes raw transaction bytes** — that is the one gap left in
  this milestone.

## Consequences

- **Correctness is a security property here.** A wrong "you will receive 340 MIN"
  is worse than no summary, because it manufactures confidence. Hence SIM-M5, and
  hence the explicit `complete` flag: the honest answer to an unresolvable input
  is to say so, not to quietly compute a smaller loss.
- **Node dependency.** Both endpoints are verified present in the pinned
  0.1.0-pre12 release, so this ships against what the wallet already bundles.
  ADR-041's gate is SIM-M0 rather than a hope: capability probing plus legible
  degradation, because the History regression of 2026-08-07 is the precedent
  and version strings from local builds mislead.
- **Latency in the signing path.** Bounded by the timeout; the degraded path must
  be a first-class outcome, tested, not an afterthought.
- **This is the flagship claim.** If it is good, it is the reason to choose this
  wallet, and it is defensible: a competitor can copy the UI but not the
  trustlessness, because they would have to ask someone else what the transaction
  does.
- Blind-signing risk is reduced, not eliminated. The user can still approve a
  transaction the summary correctly described as dangerous. The goal is to make
  that an informed choice rather than an uninformed one.
