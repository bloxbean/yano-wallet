# Yano Wallet — User Guide

Yano Wallet is a **full-node Cardano wallet for your desktop**. Unlike most
wallets, it doesn't rely on anyone else's servers: it talks only to a Cardano
node — one it can run for you, or one you already run. Your recovery phrase and
keys never leave your computer.

> **Your keys. Your node. Nothing in between.**

---

## 1. First launch — connect to a node

When you open Yano Wallet for the first time, you choose how it reaches the
Cardano network:

- **Run a local node (recommended).** The wallet starts and manages a Cardano
  node for you in the background. Nothing else to install. On a public network
  (preprod / mainnet) the node needs to catch up to the chain the first time,
  which can take a while; after that it stays up to date quickly.
- **Connect to my node.** If you already run a Yano node, enter its address
  (for example `http://localhost:7070/api/v1/`).

Pick your **network** (mainnet for real ADA; preprod/preview for testing), then
select a mode and click **Connect**. Your choice is remembered — next time the
wallet reconnects automatically.

The sidebar shows the connection status:
- **synced · block N** — the node is caught up; balances are current.
- **syncing · lag N** — the node is still catching up; balances reflect how far
  it has synced.
- **node offline** — the node isn't reachable yet (it may still be starting).

---

## 2. Create or restore a wallet

**Create a new wallet**
1. Click **Create new wallet**, give it a name, and set a **spending
   passphrase** (you'll enter this to send funds — choose something strong).
2. The wallet shows a **24-word recovery phrase**. Write it down on paper, in
   order, and keep it offline.
   > ⚠️ These 24 words are the **only** way to recover your funds if you lose
   > this computer. Anyone who has them controls your wallet. Never type them
   > into a website or share them.
3. Confirm you've saved them, then unlock with your passphrase.

**Restore an existing wallet**
1. Click **Restore from recovery phrase**.
2. Enter a name, your 12–24 word recovery phrase, and a new spending passphrase.
3. Unlock. The wallet re-derives your addresses and shows your balance once the
   node has synced.

Your keys are stored **encrypted** on this computer (Argon2id + AES-GCM), locked
by your passphrase.

---

## 3. Everyday use

### Dashboard
Your total ADA balance, any native assets (tokens), and recent activity, plus
quick **Send** / **Receive** buttons.

### Receive
Your wallet address, with a one-click **Copy**. Share it to receive ADA or
tokens. A list of additional receive addresses is shown below.

### Send
1. Paste the recipient's address.
2. Choose the **asset** — ADA, or one of your native tokens from the dropdown
   (each shows your available balance).
3. Enter the amount (ADA in decimals, e.g. `12.5`; tokens as a whole number),
   and an optional on-chain message.
4. Click **Review & sign** to see the exact amount, network fee, and total.
5. Click **Confirm & submit**. The transaction appears in **History** as
   *pending*, then *confirmed* once it's in a block.

> Sending a token automatically includes a small amount of ADA (a network
> requirement, ~1.2 ₳) that goes to the recipient along with the token.

### History
Every transaction that involved your wallet, newest first, with its status and
block. Recently sent transactions appear here immediately as *pending*.

### Staking
Earn rewards by delegating your ADA to a stake pool:
1. Enter a **pool id** and click **Delegate**.
2. The review shows the fee and, the first time you delegate, a **refundable
   ~2 ₳ deposit** to register your stake address (you get it back if you ever
   de-register).
3. Confirm. Your delegation and, over time, your **reward history** appear on
   this screen. Use **Withdraw rewards** to move accumulated rewards into your
   spendable balance.

### Settings
Shows your node connection and the active wallet's details. Your node URL and
sync status are here.

### Lock
Click **Lock** (bottom of the sidebar) to secure the wallet — you'll need your
passphrase to unlock again. Locking doesn't stop the node.

---

## 4. Good habits

- **Back up your recovery phrase offline** and never share it. Yano will never
  ask for it after setup.
- **Test on preprod first.** Preprod is a test network with free test-ADA — a
  safe place to try sending, delegating, and receiving before using mainnet.
- **Keep the node running to stay current.** When you close the wallet, a node
  it started is stopped too; the next launch resumes it.
- **Only download Yano Wallet from the official source** and verify the
  download. Fake "desktop wallet" installers are a known scam in the Cardano
  ecosystem.

---

## 5. Troubleshooting

| What you see | What it means / what to do |
|---|---|
| **node offline** for a while | The node is still starting or syncing. On a public network the first sync can take time. |
| Balance looks low or empty right after connecting | The node may still be **syncing**. Check the sidebar — balances are complete once it says **synced**. |
| **History** or **Rewards** empty on a node you connected to | That external node may not have the wallet history features enabled. A managed local node has them on by default. |
| Can't send — "Amount must be greater than zero" / "Not enough funds" | Check the amount and that your balance (minus fees) covers it. |
| Unlock fails | Wrong passphrase. There's no passphrase recovery — but your **recovery phrase** can always restore the wallet. |

For technical/developer details (running from source, the CLI, module layout),
see [`DEVELOPER.md`](DEVELOPER.md).
