# Wallet index live validation

`WalletIndexLiveTest` is opt-in and launches an isolated local devnet from a node
artifact and a devnet configuration directory. It never attaches to an existing
node. Public test mnemonics fund a fresh copied genesis. Logs and wallet state
remain in the printed temporary evidence directory; owned node processes are
stopped even when an assertion fails.

From the wallet checkout:

```sh
./gradlew :wallet-node-client:test --tests '*WalletIndexLiveTest' \
  -Dyano.wallet.live.artifact=/absolute/path/to/yano.jar \
  -Dyano.wallet.live.config=/absolute/path/to/yano/app/config/network/devnet
```

Use the native `yano` executable as the artifact to run the same test natively.
The JVM artifact must be the self-contained distribution jar. Both artifact
variants passed on 2026-09-06 against Yano PR #120. Sender and receiver have
distinct stake credentials, so outgoing-only detection cannot accidentally match
the recipient's credential.

Verified through actual wallet client/port classes: first-seen discovery including
genesis, persisted native assets/outpoints, outgoing-only history, fully spent
address use, graceful node restart, forced termination and restart, snapshot
rollback removing orphaned history/outpoints, and replacement transactions.
Wallet client objects are recreated between reads to exercise durable state;
this is not a JavaFX UI test. General tests skip the live test unless both paths
are supplied. Historical performance and complete runtime recovery coverage remain
tracked in the node issue and validation report.
