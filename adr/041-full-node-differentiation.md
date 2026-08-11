# ADR-041: What Makes This Wallet Different — Features Only a Full Node Can Offer

## Status

Proposed (direction; each candidate needs its own ADR before it is built)

## Date

2026-08-09

## Context

Yano Wallet is now a competent Cardano wallet: send/receive with native assets,
staking, CIP-1694 governance, hardware signing, multi-account, a CIP-30 dApp
connector. Every one of those is table stakes — Eternl, Lace, Yoroi, Typhon,
Vespr and Begin all have them, most with more polish, larger teams and years of
head start.

So "catch up on features" is a losing strategy. On NFT galleries, swaps, fiat
on-ramps, mobile apps and dApp integrations we will always be behind, because
those are commodity features where the competition is resourcing, not capability.

The one thing we have that none of them have is **the node**. Every other
Cardano wallet is a client pointed at somebody else's infrastructure — Blockfrost,
Koios, or a vendor-run indexer. That is not a small difference in degree; it
changes what is *possible*:

- They cannot see the mempool. They submit and hope.
- They cannot evaluate a script. They can only ask a backend to.
- They cannot compute rewards. They read whatever the indexer says.
- They cannot avoid disclosing your addresses. Every query is a linkage.

A light wallet can copy any of our UI in a sprint. It cannot copy the node.

### The filter

This gives a sharp test for what is worth building:

> **Could a light wallet ship this without trusting a third party?**
> If yes, it is not a differentiator. Build it only if users need it.
> If no, it is a moat. Build it first.

Note the "without trusting a third party" clause. A light wallet *can* show
simulated transaction effects — by asking a remote service to simulate. That is
a different product: it moves the trust, it does not remove it. Our version is
trustless because the answer comes from the user's own validated chain state.
The feature is the same shape; the guarantee is not.

### What the node already exposes

Surveyed 2026-08-09 against the Yano node source. These are not speculative:

| Capability | Where | Wallet use |
|---|---|---|
| Plutus script evaluation | `EvaluationResource` → `POST /utils/txs/evaluate` (Ogmios-shaped) | simulate before signing |
| UTxO by output reference | `GET /utxos/{txHash}/{index}` | resolve a transaction's inputs |
| Mempool | `runtime/.../chain/MemPool`, `TransactionResource` | inclusion estimates, congestion |
| Reward calculation | `EpochRewardCalculator`, `EpochStakeSnapshotService` | recompute, explain, predict rewards |
| Governance ratification | `ledger-state/.../governance/ratification` | local outcome simulation |
| L1 event stream (SSE) | `EventsResource`, `L1EventFanout` | push-based watchers |
| App-chain / L2 bridge | ADR-UTXO-008 payment-chain bridge | native L2 deposit/withdraw |

## Decision

Position Yano Wallet as **the wallet that verifies things itself**, and select
features by the filter above rather than by competitor parity.

The candidates below are ranked by (differentiation × feasibility). Each needs
its own ADR before implementation; this one records the direction and the
reasoning so the ranking is not re-litigated from scratch each time.

### 1. Simulate before you sign — **flagship, see ADR-042**

Blind signing is the largest unsolved safety problem in Cardano. Every wallet
shows a dApp transaction as a partially decoded blob and a **Sign?** button.
Users are drained because nobody can tell them what a transaction actually does.

Yano can evaluate the transaction against the user's own ledger state and show
the real net effect before signing. `TxEvaluationGateway` already does the Plutus
half; the value diff and risk heuristics are wallet-side work.

*"Your node checks the transaction before you do."*

### 2. Reward forensics and counterfactual delegation

Other wallets *read* rewards from an indexer. We can *recompute* them —
`EpochRewardCalculator` is verified against mainnet epochs 209–550.

That enables answers nobody else can give: why this reward was this size (pool
performance, saturation, pledge, `a0`, decentralisation), what next epoch pays
before it is paid, and **"what if I delegated to pool X"** run through the real
ledger formula on real snapshot data rather than a website's estimate.

The strongest "delight" feature on the list, and the most shareable.

### 3. Mempool-aware sending

Light wallets are blind after submit. With `MemPool` we can show live congestion
and fee distribution, report *"in the mempool, expected in ~2 blocks"*, and name
the failure modes users currently experience as silence: dropped, replaced, or
will-fail-on-chain.

### 4. Governance with computed outcomes

Instead of listing proposals, evaluate them locally: if the epoch ended now,
would this pass? Show the tally by DRep / SPO / CC against thresholds, and the
marginal effect of *this wallet's* voting power. Computing a marginal effect
needs the full DRep distribution, which is exactly what an indexer-backed wallet
lacks.

### 5. Privacy as a visible, provable property

We already leak nothing; today that is invisible. Make it concrete ("this
session: 0 third-party requests", against what a light wallet would have
disclosed). The practical companion is complete, untruncated history export for
tax purposes — indexer-backed wallets truncate and rate-limit; we have the chain.

### 6. Watchers on the event stream

`EventsResource` already pushes L1 events. Let users subscribe: alert when an
address moves, when a policy mints, when their pool changes margin or pledge.
No polling, no API quota — and a natural fit for the node's plugin system.

### Explicitly not pursuing

Swaps, fiat on-ramp, NFT gallery, in-wallet dApp browser, mobile. All are
commodity, all are better served by the incumbents, and none of them get better
because there is a node underneath.

## Consequences

- **Feature work now depends on node releases.** Items 1, 3, 4 and 6 need node
  endpoints beyond what a released Yano serves today. The History-page failure
  of 2026-08-07 — where the wallet called endpoints that existed in no published
  Yano build — is the pattern to avoid repeating. The wallet must declare a
  **minimum node version** and degrade legibly below it, rather than surfacing a
  raw 404. This is a prerequisite for everything here, not a detail.
- **The differentiators are also the hard parts.** Value diffing, reward maths
  and ratification rules are all places where being subtly wrong is worse than
  being absent: a confident wrong answer about what a transaction does is a
  security defect. Each of these needs verification against real chain data, and
  the reward engine's existing epoch-by-epoch mainnet verification is the model.
- **Marketing follows capability, not the reverse.** Each claim here should be
  demonstrable on request ("show me") rather than asserted in a feature list.
- This ADR does not commit to building any of it. It commits to the filter, so
  that a future "should we add swaps?" has an answer already.
