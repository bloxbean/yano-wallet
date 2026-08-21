# ADR-043 — Local transaction history when the backend indexes none

**Status:** accepted, built · **Date:** 2026-08-19 · **Supersedes nothing; narrows ADR-033 M2**

## Context

ADR-033 M2 assumed the wallet reads history from the node:
`GET /accounts/{stake}/transactions` for a Yano node,
`GET /addresses/{address}/transactions` for a Blockfrost-shaped backend
(ADR-038). The second holds — yaci-store and Yaci DevKit serve it.

The first does not. Checked against the **released artifact** — the class list
of the release jar, not the source tree, because that distinction turned out to
matter (see the note under the table):

| Route | pre12 | pre13 |
|---|---|---|
| `/accounts/{stake}/transactions` | absent | absent |
| `/accounts/{stake}/rewards` | absent | absent |
| `/txs/{hash}/status` | absent | absent |
| `/txs/{hash}` | present | present |
| `/addresses/{address}/utxos`, `/utxos/{tx}/{ix}`, `/tx/submit`, `/utils/tx/evaluate` | present | present |

> **Check the jar, not the version string.** An unreleased post-pre13 build
> carries `AddressResource`, `HistoryResource` and `EventsResource` and answers
> all of the above; the `v0.1.0-pre13` release jar contains none of those
> classes. Same version number, different answers. The check is
> `unzip -l yano.jar | grep -E 'AddressResource|HistoryResource'`.
>
> That build also shows how the routes will arrive: behind per-dataset switches,
> answering `503 {"error":"Reward history disabled (enable
> yano.history.datasets.rewards.enabled)"}` when off. The wallet launches its
> managed node with `quarkus.profile=<network>` and passes no such flag, so
> **switched-off is the shape wallet users will meet first** — which is why 503
> falls back exactly like 404 rather than reporting an error. When those property
> names are settled upstream, passing them at launch (`ManagedNode`) is the
> better fix and retires the fallback for managed nodes.

So against every published Yano release the History screen showed
`History unavailable: GET … failed with status 404` and Recent Activity showed
the same. The wallet's flagship paths — balances, sending, simulate-before-you-
sign, the CIP-30 bridge — all worked; only the record of what had been done was
missing. That is a poor trade to leave standing while the node catches up
(tracked in `project_node_version_dependency`; Yano PR #58 is the fix upstream).

The missing `/txs/{hash}/status` was worse, and quieter. `WalletService`'s
confirmation tracker polls it, catches errors as transient, and gives up at
`PENDING_TIMEOUT_MILLIS`. Every poll 404'd, so **a transaction that was in a
block was shown as pending and then marked failed five minutes later.**

## Decision

When the backend serves no transaction index, History and Recent Activity show
the wallet's own record of what it submitted, clearly labelled — instead of an
error.

Three parts.

**1. The wallet already had the data.** `FilePendingTransactionStore`
(`~/.yano-wallet/<network>/pending-transactions.json`) records every transaction
this wallet sends: the QuickTx path (`WalletService.Session#submit`), the
hardware paths (`recordSubmittedPayment`), and dApp submissions through the
CIP-30 bridge (`WalletCip30Wallet#submitOnce`). No new store, no new file
format, no second source of truth — the "local history provider" is the pending
store, read differently. What changed is that the store is now also a *log*: in
this mode records are never forgotten, because there is no node history for them
to graduate into.

**2. Missing index is a distinct answer, not an error.**
`HistoryPort.HistoryNotSupportedException` (a subclass of
`HistoryUnavailableException`) is thrown when the history route answers 404 (not
there) or 503 (there, switched off); anything else keeps reporting as before.
Only the subclass triggers the fallback. This is the line that has to be right:
a node that hiccuped still has real history, and answering that with a partial
local list would hide transactions rather than report a problem the user can act
on.

A 404 is not cached as "this backend has no index". A backend that serves the
route can still 404 an account it has never seen, so the fallback re-evaluates
on every refresh and a wallet that later transacts flips back to real history
by itself — the same "cache only the good answer" shape as
`SimulationCapabilities`.

**3. `/txs/{hash}` replaces `/txs/{hash}/status` outright.**
`YanoNodeClient#getTxStatus` reads the Blockfrost-standard route and does not
consult the Yano-specific one at all — not even opportunistically first.
`/txs/{hash}/status` is **deprecated upstream and will be removed**, and it was
already the less available of the two:

| | `/txs/{hash}/status` | `/txs/{hash}` |
|---|---|---|
| v0.1.0-pre13 release jar | absent — no `getTxStatus` in `TransactionResource` | present |
| post-pre13 build, live preprod | `503 {"status":"incomplete","detail":"transaction history is disabled or unavailable"}` | `200` with block hash/height/time/slot |
| yaci-store / Yaci DevKit | absent (not Blockfrost-standard) | present |

One route therefore serves every backend, and each poll costs one call instead
of two. This is what makes confirmation work at all — with it, local rows go
`pending → confirmed`; without it, every one of them decayed to `failed` five
minutes after being submitted.

An earlier draft tried `/status` first and fell back only on 404. That would not
have worked: the live node answers 503, which threw rather than falling back.
Found by probing a running node, not by reading source — route *absence* and
route *disabled* are different failures, and a real backend shows you both.

**404 and 503 mean different things here, deliberately unlike the history
routes.** On `/txs/{hash}` a 404 is definitive — the node looked and does not
have this transaction in a block — and maps to `UNKNOWN`. A 503 means the UTxO
index is off and the node *cannot look*; that throws, so `reconcile` leaves the
record untouched. Reading it as "not on chain" would mark a confirmed
transaction failed on the strength of a question never asked. On the history
routes there is no such risk — nothing is destroyed by showing a labelled local
list — so both codes fall back there.

What `/txs/{hash}` cannot do is separate "in the mempool" from "never seen" — a
transaction appears there only once it is in a block. Nothing depends on that:
callers act on `IN_BLOCK`, and the wallet already knows locally what it
submitted.

`WalletService#localHistory` reconciles on read, not only in the tracker: the
tracker dies with the process, so a transaction submitted and then quit on would
otherwise sit unconfirmed forever. A failed lookup leaves the record alone
rather than letting it expire — calling a live transaction "failed" because a
localhost call blipped is the one outcome worth going out of the way to avoid.
Lookups are bounded to 20 per refresh, since a reset devnet can leave dozens of
records stuck and History refreshes on every dashboard tick.

## What the user sees

`WalletUiController.history` returns `HistoryPage(items, localOnly)`. When
`localOnly`, History shows a `Local history only` chip and:

> This node keeps no transaction index, so this list is the wallet's own record
> of what it sent — including transactions a connected dApp submitted through
> it. Funds received from elsewhere are counted in your balance but do not
> appear here.

Recent Activity carries the short form of the same. The received-funds sentence
is the load-bearing one: the balance is complete and the list is not, and a user
comparing the two without being told would reasonably conclude the wallet had
lost a transaction.

## Consequences

- **Incoming transactions are not listed.** Detecting them needs an index; that
  is the thing the node does not have. Not worked around — named.
- **Nothing is retrospective.** Transactions sent before this shipped, or from
  another wallet on the same seed, are not in the store and cannot appear.
- **Expiry is gated on the node being at the tip.** The five-minute timeout
  assumes the node can see the chain tip; one still catching up cannot, so a
  transaction already in a block reads as "not found" and would be marked failed.
  Observed live on an embedded node ~6,400 blocks behind. `localHistory` asks
  `/status` once per page and skips expiry unless `caughtUp`; a node that cannot
  be asked counts as not at the tip.
- **A timed-out "failed" is a guess, and must stay revisable.**
  `awaitsConfirmation()` is false once failed, so nothing looked again and the
  wrong verdict outlived the sync that would have overturned it — the balance
  caught up while the row still read failed. Timeout-failures are now re-checked
  (identified by their message prefix, so records already on disk recover too)
  and flip to confirmed if the node has them in a block; a transaction the node
  actually rejected keeps its reason and is never second-guessed. In-flight
  records take the lookup budget first.
- **One gap in reconciliation.** `/txs/{hash}` is served out of the UTxO index
  (`getOutputsByTxHash`), which scans both the unspent and the spent column
  families — but the spent one is pruned at `utxo.prune.pruneDepth` (2160 blocks
  ≈ 12 h, Cardano's `k`). So a transaction resolves while any output is unspent
  **or was spent within ~12 hours**, and 404s permanently after that. Observed on
  preprod: a transaction with unspent outputs answers 200, while the same node
  returns an empty `inputs` list for it because its *parents* were spent long
  enough ago to have been pruned. An open wallet reconciles within a refresh of
  the block landing, so this only bites a wallet closed across both the spend and
  the prune window.
- **Rewards are untouched.** `/accounts/{stake}/rewards` is equally absent and
  Staking still reports it as unavailable. There is no local equivalent to fall
  back to — the wallet never sees a reward being paid — so there is nothing
  honest to show. It waits for the node.
- **yaci-store is unchanged** where its route answers 200, which is the case
  the flavor exists for.
- **This is a bridge.** When a Yano release serves the index, the fallback stops
  firing on its own — no flag to unset. `BlockfrostFlavorPathTest` and
  `PublishedNodeHistoryGapTest` pin both sides.
