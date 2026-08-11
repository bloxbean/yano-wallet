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

## Load it (development)

The desktop bridge (Yano wallet) must be running and unlocked.

1. Chrome/Edge/Brave: `chrome://extensions` → enable **Developer mode** →
   **Load unpacked** → select this `wallet-connector/` folder.
2. Open a dApp, pick **Yano** in its wallet list, and approve the connection in
   the desktop app.

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
  Note: `manifest.json` pins the id via its `key`; the matching private key
  (`extension-key.pem`) stays out of git — keep it safe for store publishing.
  Verify the transport with `lsof ~/.yano/cip30.sock` while a dApp is connected.
- **Multi-account (ADR-037):** dApps see whichever account is *currently open* in
  the wallet — the connector reads the live session, so it has no account of its
  own. Switching accounts in the wallet changes what a connected dApp gets on its
  next call, and CIP-30 has no account-changed event to announce it; a dApp that
  cached addresses may need a reconnect. The dApp allowlist is per-origin, not
  per-account: approving a site once approves it for any account you open.
