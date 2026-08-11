# ADR-039: Native Messaging Host — Cross-Platform Registration (Windows + Firefox)

## Status

Proposed (plan only — not yet implemented)

## Date

2026-07-27

## Context

ADR-035 M5 landed the CIP-30 **Native Messaging** transport: Chrome launches a
zero-dep relay (`wallet-connector-proxy`) that pipes its stdio to the wallet's
unix domain socket (`~/.yano/cip30.sock`); `NativeMessagingInstaller` registers
the host with the browser, and the wallet now defaults to this transport with
the localhost WebSocket opt-in (ADR-035 transport decision, `--enable-ws-connector`).

That work covers **macOS + Linux** with **Chromium-family browsers**
(Chrome/Chromium/Edge/Brave). Two gaps remain, both entirely in our own code —
no third-party dependency or service is involved. The browsers simply look for
the host in different places on different platforms, and our installer has to
write to those places.

- **Windows (Chromium browsers).** `NativeMessagingInstaller.install()` throws on
  Windows today.
- **Firefox (all OSes).** Not wired for any platform.

This ADR captures *how* each is registered, the portability landmines in the
current code, and — the load-bearing question — **how we validate platforms we
don't own a machine for.**

### How host registration differs per platform

The extension side is unchanged: `chrome.runtime.connectNative('com.bloxbean.yano.cip30')`
works in both Chrome and Firefox. Only *where the browser looks for the host
manifest* changes. The manifest fields also differ (Chrome keys on the extension
**origin**, Firefox on the add-on **id**):

| Browser / OS | Where the browser looks | Manifest identity field |
|---|---|---|
| Chromium, macOS | `~/Library/Application Support/<vendor>/NativeMessagingHosts/<name>.json` | `allowed_origins: ["chrome-extension://<id>/"]` |
| Chromium, Linux | `~/.config/<vendor>/NativeMessagingHosts/<name>.json` | `allowed_origins: […]` |
| **Chromium, Windows** | **registry** `HKCU\Software\<vendor>\NativeMessagingHosts\<name>` (default value = absolute path to the manifest `.json`) | `allowed_origins: […]` |
| **Firefox, macOS** | `~/Library/Application Support/Mozilla/NativeMessagingHosts/<name>.json` | `allowed_extensions: ["<addon-id>"]` |
| **Firefox, Linux** | `~/.mozilla/native-messaging-hosts/<name>.json` | `allowed_extensions: […]` |
| **Firefox, Windows** | **registry** `HKCU\Software\Mozilla\NativeMessagingHosts\<name>` (default value = manifest path) | `allowed_extensions: […]` |

macOS + Linux Chromium (the top two rows) are the shipping baseline. Everything
in **bold** is this ADR.

Per-vendor Windows registry roots (all under `HKCU\Software\…\NativeMessagingHosts\com.bloxbean.yano.cip30`):
`Google\Chrome`, `Microsoft\Edge`, `BraveSoftware\Brave-Browser`, `Chromium`, and
`Mozilla` (Firefox). We write only the roots whose browser is actually installed
(mirroring today's macOS/Linux "skip if the parent dir is absent" rule), except
Chrome, which we always write (it may install later).

### Portability landmines in the current code (must fix for Windows)

1. **POSIX perms throw on Windows.** `NativeMessagingInstaller.writeLauncherScript`
   calls `Files.setPosixFilePermissions(...)` unconditionally → `UnsupportedOperationException`
   on Windows. The Windows branch must not call it. (The socket server's
   `restrictToOwner` already guards this correctly — the installer does not.)
2. **The launcher must be a `.bat`, not `#!/bin/sh`.** Windows can't exec the
   shell script. We write `cip30-host.bat`:
   `"<java.home>\bin\java.exe" -cp "<proxyJar>" com.bloxbean.cardano.yano.wallet.connector.proxy.Cip30NativeProxy "<socket>" %*`
   and point the manifest `path` at it (absolute).
3. **Registry writes need a mechanism.** `java.util.prefs.Preferences` can only
   write under `HKCU\Software\JavaSoft\Prefs` — it cannot create the Chrome key.
   Options: (a) shell out to `reg.exe add "<key>" /ve /t REG_SZ /d "<path>" /f`,
   or (b) add a JNA/native dependency. **Choose `reg.exe`** — it keeps the
   zero-dependency philosophy of the proxy/host and is trivially unit-testable
   behind a command-runner seam.
4. **Socket path length & ACLs.** `C:\Users\<user>\.yano\cip30.sock` is well
   under the AF_UNIX path limit. POSIX owner-only perms don't apply; we rely on
   `%USERPROFILE%\.yano` inheriting the profile's ACLs (already the documented
   fallback in `Cip30LocalSocketServer`).
5. **Minimum Windows version.** Java's `UnixDomainSocketAddress` needs AF_UNIX,
   i.e. **Windows 10 build 1803+ / Server 2019**. Document it; older Windows
   falls back to `--enable-ws-connector`.

### Firefox specifics (all OSes)

- **Add-on id is Firefox's identity anchor.** Chrome's pinned `key` doesn't
  apply; the extension needs a stable id in
  `browser_specific_settings.gecko.id` (e.g. `yano-connector@bloxbean.com`).
  That id — not an origin — goes in the host manifest's `allowed_extensions`.
  Picking it is a one-time public commitment, like the Chrome pinned key.
- **Manifest shape** swaps `allowed_origins` for `allowed_extensions`; the
  `name`/`description`/`path`/`type` fields are identical.
- **Signing.** Released Firefox installs only load AMO-signed add-ons (or
  Developer Edition/ESR with `xpinstall.signatures.required=false`). This is a
  distribution concern (store track), not a transport one, but it gates a real
  end-user Firefox test.

### Testing without owning every platform — the crux

We can't run a full Chrome-launches-the-host test without a Windows-with-a-browser
box. But most of the risk is in **string/path/registry-key construction**, which
is testable anywhere if we make the environment injectable. Three layers:

- **L1 — pure-logic unit tests, run on any OS (Mac/CI now).** Refactor
  `NativeMessagingInstaller` so the **OS**, **home dir**, and a **command runner**
  (the thing that would invoke `reg.exe`) are injected (the constructor already
  takes `yanoDir` — extend that seam). Then assert, from macOS, the *Windows*
  outputs:
  - exact `.bat` launcher content,
  - manifest JSON content + shape (Chrome `allowed_origins` vs Firefox
    `allowed_extensions`),
  - the exact `reg add` command lines that *would* run (captured by a fake
    runner, never executed),
  - correct target locations/keys per browser, and that absent browsers are
    skipped.
  This covers the error-prone part with zero Windows.
- **L2 — real-Windows CI (GitHub Actions `windows-latest`, free).** A JVM test
  that runs the actual install against a **disposable registry subtree**, then
  reads it back with `reg query` to prove the key exists and points at the
  manifest — real `reg.exe` on genuine Windows, no owned machine. Add
  `windows-latest` (+ macOS + Ubuntu) to the wallet test matrix so the whole
  build, the UDS bind, and these tests run on real Windows in CI, catching the
  POSIX-perm / `.bat` / path landmines automatically. CI can't drive an
  interactive Chrome, so it stops short of the launch itself.
- **L3 — full E2E, deferred to a Windows VM or a beta tester.** The only piece
  L1+L2 can't reach: install from Settings → restart Chrome → connect a dApp →
  confirm Chrome launches the host and traffic flows (`Get-Process` / Sysinternals
  for the process, and the wallet's approval prompt as the functional proof). Run
  on a free Windows eval VM (Parallels/UTM/VirtualBox) or a tester at packaging
  time.

Net: L1 ships with the code, L2 gives real-Windows confidence cheaply and
continuously, L3 is a one-time manual gate before the Windows release.

## Decision

Generalize host registration behind **one platform-strategy seam** in
`NativeMessagingInstaller` and add the Windows (registry + `.bat`) and Firefox
(Mozilla locations + `allowed_extensions` + gecko id) branches. Make the whole
thing unit-testable by injecting OS, home dir, and a command runner; add a
`windows-latest` CI job that exercises the real registry write. Keep the
zero-dependency approach — Windows registry via `reg.exe`, no JNA.

The transport layer (`Cip30LocalSocketServer`, `Cip30NativeProxy`) is already
Windows-capable (AF_UNIX, guarded perms) and needs no change beyond
confirmation in CI.

## Milestones

- **NM-M1 — Windows, Chromium (Chrome/Edge/Brave/Chromium).**
  - Fix the POSIX-perms landmine (skip on Windows).
  - `.bat` launcher; manifest written to `%USERPROFILE%\.yano`; `reg.exe`
    registry writes for each installed vendor root (Chrome always).
  - Inject OS + home + command-runner seam; L1 unit tests for launcher/manifest/`reg`
    lines; L2 `windows-latest` CI test that writes + `reg query`-reads back a
    disposable key.
  - Settings "Install browser connector" summary reports Windows browsers.
  - L3 manual E2E on a Windows VM before enabling in a Windows package.
- **NM-M2 — Firefox, all OSes.**
  - Add `browser_specific_settings.gecko.id` to the extension `manifest.json`.
  - Firefox host manifest (`allowed_extensions`) at the Mozilla locations
    (macOS/Linux dirs; Windows `HKCU\Software\Mozilla\…` registry).
  - Reuse the NM-M1 seam + tests; extend L1 for the Firefox manifest shape.
  - AMO-signing captured under the store/distribution track (out of scope here).

macOS + Linux Chromium remain the already-shipped baseline; this ADR does not
change them beyond the shared refactor.

## Consequences

- One installer grows a platform-strategy shape; the payoff is that Windows and
  Firefox reuse the same manifest/launcher/registration seam and the same tests.
- Registry via `reg.exe` keeps zero runtime deps but shells out — hidden behind
  the command-runner seam, so it's mockable and the only thing that genuinely
  needs Windows is the `reg query` round-trip in CI.
- **Min OS**: Windows 10 1803+ for AF_UNIX; older Windows uses the opt-in WS.
- **Uninstall**: registry keys + files should be removable — an uninstall action
  (or the eventual OS installer) must clean `HKCU\…\NativeMessagingHosts\<name>`
  and `~/.yano` host files, or a stale key points at a deleted launcher.
- **Antivirus / SmartScreen** may flag an unsigned `.bat`/jar that Chrome
  launches; code-signing (separate release-hardening track) mitigates.
- **Firefox add-on id** is a public, permanent identifier (like the Chrome
  pinned key) — choose it once and keep it out of churn.
