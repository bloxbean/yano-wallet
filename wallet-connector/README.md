# Yano Wallet Connector (CIP-30)

A companion browser extension that lets Cardano dApps talk to the **Yano
full-node desktop wallet**. It injects `window.cardano.yano` (CIP-30) into web
pages and relays each call to the running desktop app, which holds the keys,
shows an approval, signs, and answers. See [ADR-035](../adr/035-cip30-dapp-connector.md).

The extension holds **no keys and makes no decisions** — it is a thin, typed
message bridge:

```
web dApp  ──window.cardano.yano (CIP-30)──►  inject.js (page world)
          ──window.postMessage──►            content.js (isolated world)
          ──chrome.runtime──►                background.js (service worker)
          ──ws://127.0.0.1:27428/cip30──►     Yano desktop app  → approve → sign
```

## Layout

| File | Role |
| --- | --- |
| `manifest.json` | MV3 manifest (two content scripts: MAIN-world provider + ISOLATED bridge) |
| `src/inject.js` | The `window.cardano.yano` CIP-30 provider (page context) |
| `src/content.js` | Page ↔ background relay |
| `src/background.js` | WebSocket transport to the desktop app |
| `popup.html` / `popup.js` | Connection status + bridge port |

## Install it

Until the extension is on the Chrome Web Store it is loaded unpacked, either
from a downloaded build or straight from this folder. The desktop app (Yano
wallet) must be running and unlocked.

### From a prebuilt zip (no clone needed)

1. Download `yano-connector-<version>.zip` — from a GitHub **Release**, or from
   the **Artifacts** of any `build` workflow run for a pre-release build.
2. **Unzip it.** `chrome://extensions` cannot load a `.zip`; Load unpacked wants
   a folder.
3. `chrome://extensions` → enable **Developer mode** → **Load unpacked** →
   select the unzipped folder.
4. Open a dApp, pick **Yano** in its wallet list, and approve the connection in
   the desktop app.

The extension id is the same either way (`bjnkcmbkjaebecgllkgbeapbjcknnedn`) —
it comes from the public `key` in `manifest.json`, which travels in the zip, so
the wallet's native messaging host recognises a zip install exactly as it does a
folder install. Chrome will still show the "developer mode extensions" warning
on each launch; that is unpacked loading, not this build.

Build the same zip locally with `./gradlew connectorZip` (lands in
`build/dist/`).

### From this folder (development)

`chrome://extensions` → **Developer mode** → **Load unpacked** → select this
`wallet-connector/` directory. Edits need only a reload of the extension.

Firefox: `about:debugging` → **This Firefox** → **Load Temporary Add-on** →
pick `manifest.json`.

## Notes

- The legacy localhost WebSocket (`ws://127.0.0.1:27428/cip30`) is now **off by
  default** — Native Messaging is the only transport unless the wallet is started
  with `--enable-ws-connector` (or `-Dyano.connector.ws=true`). When enabled, the
  port is loopback-only and the page never connects to it (only the extension
  does); it stays changeable in the popup. The extension prefers Native Messaging
  and only falls back to this socket, so with the flag off an un-installed native
  host means the dApp simply can't connect (rather than silently using the WS).
- **Native Messaging (preferred transport, ADR-035 M5):** in the wallet's
  Settings, click **Install browser connector** and restart the browser. Chrome
  then launches the wallet's relay itself (verifying this extension's pinned id
  `bjnkcmbkjaebecgllkgbeapbjcknnedn`) and talks over `~/.yano/cip30.sock` — no
  localhost port. The background worker falls back to the WebSocket
  automatically when the host isn't installed, so nothing breaks either way.
  Note: `manifest.json` pins the id via its **public** `key`, which is all an
  unpacked load (folder or zip) needs. That string is committed, so the id
  survives in git on its own — no build, local or CI, has ever read the private
  `extension-key.pem` (it was used once, offline, to generate the pair, and
  `.gitignore` keeps it out). The pem signs only a `.crx`, which Chrome refuses
  to install from outside the store, so losing it costs nothing that is
  reachable today; back it up if it is to hand, but do not go hunting.
  Verify the transport with `lsof ~/.yano/cip30.sock` while a dApp is connected.

## Before submitting to the Chrome Web Store

The zip from `./gradlew connectorZip` is already the shape the store accepts
(manifest at the archive root, no `dev/` demo, no repo README). What is *not*
carried over is the identity:

**The store mints its own id, and `bjnkcmbkjaebecgllkgbeapbjcknnedn` will not
survive submission.** The Web Store signs uploads with its own key pair and
derives the published id from that, so the id above is a development identity
only. The supported order is the reverse of what you would expect — publish
first, then copy the public key the Developer Dashboard shows you back into
`manifest.json`'s `key`, at which point local unpacked loads share the store id.

So the submission runbook is:

1. Strip the key for the upload: `jq 'del(.key)' manifest.json > tmp && mv tmp manifest.json`
   inside a copy of the unzipped build. Uploads carrying `key` are sometimes
   rejected with *"key field is not allowed in manifest"* — reports of when it
   fires are inconsistent, and it is ignored when it does not, so removing it is
   the one move that always works. (Do **not** put `extension-key.pem` in the
   package to try to keep the id; that folklore predates current store signing
   and would hand Google your private key for nothing.)
2. Take the store's public key from the dashboard and commit it as the new `key`
   in `manifest.json`. **From then on the store id is the id everywhere** —
   local unpacked loads, CI zips and the store install all derive it, so there
   is one identity to reason about rather than two. Later uploads may keep the
   field. Don't run the store copy and an unpacked copy side by side once they
   share an id; remove one while working on the other.
3. **Update the native messaging allowlist**, or the extension silently loses
   its transport for every store user: `NativeMessagingInstaller.EXTENSION_ID`
   is baked into the host manifest's `allowed_origins`. That field takes a
   *list*, so allowlist the old and new ids together — sideloaded testers keep
   working and store users start working. Tracked as BACKLOG E21.

Step 3 is the one with teeth, and it has an **ordering constraint**. The host
manifest is written by the *wallet*, not the extension, and only when the user
clicks **Install browser connector** in Settings — nothing rewrites it on
launch. So a user who installs from the store while running an older wallet has
a host manifest naming only the old id, and Chrome refuses to start the host for
the new one. With `--enable-ws-connector` off (the default) there is no fallback:
dApps simply cannot connect and nothing in the UI explains why.

Therefore ship the wallet release carrying both ids **before** the listing goes
public, and treat "re-run Install browser connector" as a release note. Making
the wallet rewrite the host manifest at startup when its contents differ (cheap
and idempotent) would remove that manual step permanently — worth doing before
submission rather than after.
- **Multi-account (ADR-037):** dApps see whichever account is *currently open* in
  the wallet — the connector reads the live session, so it has no account of its
  own. Switching accounts in the wallet changes what a connected dApp gets on its
  next call, and CIP-30 has no account-changed event to announce it; a dApp that
  cached addresses may need a reconnect. The dApp allowlist is per-origin, not
  per-account: approving a site once approves it for any account you open.
