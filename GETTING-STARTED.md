# Yano Wallet — Getting Started

A full-node Cardano wallet: it runs its own Yano node, so your balances and
transactions come from a chain you validated yourself rather than from someone
else's API.

This guide takes you from a GitHub release to a dApp connected to your wallet.

> [!WARNING]
> **Test networks only — do not use this wallet with real funds.**
>
> Yano Wallet is under active development and has not been audited. Use it with
> a local development network (**Yaci DevKit**, **Yano DevNet**) or a public
> test network (**Preprod**, **Preview**), where ADA has no value.
>
> **Do not use it on Mainnet, and do not restore a recovery phrase that holds
> real funds.** Create a fresh wallet for testing instead.

---

## 1. Pick a download

### macOS: use Homebrew

```bash
brew install --cask --no-quarantine bloxbean/tap/yano-wallet        # native build — smallest, starts fastest
brew install --cask --no-quarantine bloxbean/tap/yano-wallet-jvm    # JVM build — installs into /Applications
```

**`--no-quarantine` matters.** Homebrew *adds* the quarantine flag by default, so
without it macOS says *"Apple could not verify 'yano-wallet' is free of malware"*
and you have to approve the app under System Settings → Privacy & Security. The
flag skips that, because these builds are not yet signed and notarised.

Already installed without it? Clear it once, no re-download needed:

```bash
xattr -dr com.apple.quarantine "$(brew --prefix)/Caskroom/yano-wallet"
```

Then **skip to step 3**. Upgrade later with `brew upgrade --cask yano-wallet`.

Both casks need **Apple Silicon**; on an Intel Mac use the portable zip below.
Install `yano-wallet-jvm` if the native build gives you trouble — it is the same
wallet on a bundled Java runtime.

### Everything else

Everything is on the [Releases page](https://github.com/bloxbean/yano-wallet/releases).
Each release carries several assets — you need **one wallet** plus **the browser
connector** (only if you want dApps to talk to the wallet).

| you want | download | size | needs Java? |
|---|---|---|---|
| **the simplest thing** — unzip and run | `yano-wallet-native-<os>_<arch>_<version>.zip` | ~160 MB | **no** |
| a normal installer, in Applications / Start Menu | `yano-wallet-<os>_<arch>_<version>.dmg` / `.msi` / `.deb` | ~340 MB | **no** |
| you already run Java 25, or your platform has no native build | `yano-wallet-<version>-portable.zip` | ~325 MB | **yes — JDK 25+** |
| dApp support (add to any of the above) | `yano-connector-<version>.zip` | small | no |

The portable zip has **no `<os>_<arch>`** — one archive runs everywhere. It is
not the smallest download: the native zip is half its size and needs no Java.
Reach for portable when you want the JVM build, or on **Linux arm64**, which has
no native build.

Pick the `<os>_<arch>` matching your machine:

| machine | `<os>_<arch>` |
|---|---|
| Mac (Apple Silicon — M1/M2/M3/M4) | `macos_arm64` |
| Linux, Intel/AMD 64-bit | `linux_x86_64` |
| Windows 64-bit | `windows_x86_64` |
| Linux, ARM 64-bit | `linux_arm64` — installer or portable zip only, no native build |
| Mac (Intel) | installer or portable zip only, no native build |

> **Native vs portable.** The *native* zip is an ahead-of-time compiled build
> that needs nothing installed — no Java, no runtime, and it is the smallest of
> the three. The *portable* zip runs on a **Java 25+** runtime you provide, and
> the wallet's local node runs under that same Java, so a complete JDK/JRE 25 is
> required.

---

## 2. Run the wallet

### Native zip

```bash
unzip yano-wallet-native-macos_arm64_<version>.zip
cd yano-wallet-native-<version>
./run.sh                  # Windows: run.bat
```

### Portable (bring-your-own-Java) zip

```bash
java -version             # must be 25 or newer
unzip yano-wallet-<version>-portable.zip
cd yano-wallet-<version>
./run.sh                  # Windows: run.bat
```

### Installer

Open the `.dmg` / `.msi` / `.deb` and install as usual, then launch **Yano
Wallet** from Applications / Start Menu / your app menu.

### If your OS blocks it

These builds are **not yet code-signed**, so the first launch is blocked:

- **macOS** — *"cannot be opened because it is from an unidentified developer."*
  Right-click the app or script → **Open** → **Open** again. Or clear the
  quarantine flag once:
  ```bash
  xattr -dr com.apple.quarantine yano-wallet-native-<version>
  ```
  One command covers the whole folder — it clears both the wallet binary and
  the bundled node, which would otherwise prompt separately.
- **Windows** — SmartScreen shows *"Windows protected your PC"*. Click
  **More info** → **Run anyway**.
- **Linux** — if the launcher is not executable:
  `chmod +x run.sh yano-wallet yano-node/yano`

---

## 3. First run: connect to a network

You land on the **Connect** screen.

1. Choose a **network** — use **Preprod** or **Preview** (public test networks
   with free test ADA), or a local **Yaci DevKit** / **Yano DevNet**. Not
   Mainnet: see the warning above.
2. Choose how to reach the chain:
   - **Run a local node** — the wallet starts and manages its own Yano node.
     This is the full-node experience and needs no external service.
   - **Connect to my node** — point at a Yano node or Yaci DevKit you already
     run, by URL.
3. Click **Start**.

> **The first sync takes a long time.** A local node must download and validate
> the chain from scratch — expect **hours** on preprod or mainnet, and a long
> stretch where it is rebuilding its indexes before the wallet can query it. The
> screen shows the phase, block position and an ETA. You can create or restore a
> wallet while it runs; balances appear once the node is ready. Later starts are
> much faster because the chain data is already on disk.

---

## 4. Create or restore a wallet

- **Create a new wallet** — you get a **24-word recovery phrase**. Write it down
  and store it offline. It is the only way to recover the wallet if you lose
  this machine, and nobody can recover it for you.
- **Restore an existing wallet** — enter your 24 words. Only ever restore a
  **test** wallet here; never a phrase that holds real funds.
- **Connect a hardware wallet** — Ledger over USB. Unlock the device and open
  the **Cardano app** first, then click **Connect**. Keys never leave the device.

Your wallet is encrypted with your passphrase and stored under
`~/.yano-wallet/`. Optionally add a **YubiKey / FIDO2 security key** as a second
factor in **Settings → Security key** — the vault then needs the key *and* the
passphrase.

---

## 5. Install the browser extension (for dApps)

Only needed if you want web dApps to use this wallet. The extension is not on
the Chrome Web Store yet, so it is installed unpacked.

1. Download `yano-connector-<version>.zip` from the same release and unzip it.
   You get a folder containing `manifest.json`.
2. Open **`chrome://extensions`** (or `edge://extensions`, `brave://extensions`).
3. Turn on **Developer mode** (top right).
4. Click **Load unpacked** and select the **unzipped folder** — the one with
   `manifest.json` directly inside it.
5. The extension appears as **Yano Wallet Connector**.

> Keep the folder where it is. Chrome loads an unpacked extension from that path
> every time it starts; deleting or moving the folder disables it.

---

## 6. Connect the extension to the wallet

The extension does not talk to the wallet over the network. It uses **Native
Messaging**: the browser launches a small helper the wallet installs, which
relays messages to the wallet over a local socket. This must be registered once.

1. In the wallet, open **Settings → Browser connector**.
2. Click **Install browser connector (Native Messaging)**.
3. **Restart your browser completely** — browsers read this registration only at
   startup.

That writes a launcher into `~/.yano-wallet/connector/` and registers it with
Chrome, Chromium, Edge and Brave.

**Re-run this step whenever you move the wallet**, upgrade to a build in a
different folder, or switch between the native and portable builds — the
registration points at an exact path on disk.

---

## 7. Try it

1. Make sure the wallet is **running and unlocked**.
2. Open the **[demo dApp](https://bloxbean.github.io/yano-wallet/tx-demo/)** and click
   **Connect Yano**. Then try **Send ₳2 to myself** — a real transaction, built
   in the browser, signed and submitted through your wallet.
3. The wallet shows an approval prompt. Approve it.

Any dApp supporting CIP-30 works the same way: choose **Yano** in its wallet
list. The demo exists because the extension is not in the Chrome Web Store yet,
so most dApps do not list Yano — it gives you something to check your install
against.

Every signature is approved in the wallet, on your machine. The dApp never sees
your keys or your recovery phrase.

---

## Where your data lives

| path | what |
|---|---|
| `~/.yano-wallet/` | everything the wallet owns |
| `~/.yano-wallet/<network>/wallets/` | encrypted wallet vaults |
| `~/.yano-wallet/<network>/node/` | the local node's chain data (**large** — tens of GB) |
| `~/.yano-wallet/connector/` | the browser connector helper |
| `~/.yano-wallet/wallet.log` | the log to check when something fails |

Run with `--data-dir=/some/path` to keep everything somewhere else — useful for
a throwaway test wallet that cannot touch your real one.

**Back up your 24-word recovery phrase, not this folder.** The phrase restores
the wallet anywhere; a copied folder still needs the passphrase and is easy to
let go stale.

---

## If something goes wrong

**"Yano wallet is not reachable — is it running and unlocked?"** in a dApp
- Is the wallet running and unlocked?
- Did you restart the browser after installing the connector?
- Did you move the wallet, or switch between native and portable builds? Re-run
  **Install browser connector**, then restart the browser.

**The dApp does not list Yano**
- Check the extension is enabled at `chrome://extensions`.
- Reload the dApp page after enabling it.

**Balance is empty / "node not ready"**
- A first sync takes hours. Check the node status at the bottom of the sidebar.

**Ledger is not detected**
- Unlock the device and open the **Cardano app** before clicking Connect.
- Use a data-capable USB cable (some cables are charge-only) and avoid hubs.

**Anything else** — `~/.yano-wallet/wallet.log` records the full error. Include
it when reporting an issue.

---

## Getting test ADA (preprod)

Use the [Cardano testnet faucet](https://docs.cardano.org/cardano-testnets/tools/faucet/),
select **Preprod**, and paste an address from the wallet's **Receive** screen.
