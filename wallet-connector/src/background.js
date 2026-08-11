// Yano CIP-30 bridge — background service worker.
//
// The only component that talks to the Yano desktop app. Preferred transport is
// Chrome Native Messaging (ADR-035 M5): Chrome launches the wallet's registered
// host process, which relays to the running app over a local socket — Chrome
// verifies OUR extension id against the host manifest, and no TCP port is open.
// When the host isn't installed yet, it falls back to the legacy localhost
// WebSocket so existing setups keep working.
'use strict';

const NATIVE_HOST = 'com.bloxbean.yano.cip30';
const DEFAULT_PORT = 27428; // legacy WebSocket fallback port
const REQUEST_TIMEOUT_MS = 120000;

let nativePort = null;       // connected native-messaging port
let nativeUnavailable = false; // remembered once per SW life: skip straight to WS
let nativeConnecting = null;
let socket = null;
let connecting = null;
const pending = new Map();

// --- Native Messaging transport ---

function connectNative() {
  if (nativePort) return Promise.resolve(nativePort);
  if (nativeConnecting) return nativeConnecting;
  nativeConnecting = openNative();
  nativeConnecting.finally(() => { nativeConnecting = null; });
  return nativeConnecting;
}

function openNative() {
  return new Promise((resolve, reject) => {
    if (nativeUnavailable) return reject(new Error('native host unavailable'));
    let port;
    try {
      port = chrome.runtime.connectNative(NATIVE_HOST);
    } catch (e) {
      nativeUnavailable = true;
      return reject(e);
    }
    let settled = false;
    port.onMessage.addListener((data) => {
      if (!settled) { settled = true; nativePort = port; resolve(port); }
      const entry = pending.get(data && data.id);
      if (entry) {
        pending.delete(data.id);
        entry.resolve(data);
      }
    });
    port.onDisconnect.addListener(() => {
      const reason = chrome.runtime.lastError ? chrome.runtime.lastError.message : 'disconnected';
      if (nativePort === port) nativePort = null;
      if (!settled) {
        settled = true;
        // "Specified native messaging host not found" → not installed: use WS.
        if (/not found|not installed|forbidden/i.test(reason || '')) nativeUnavailable = true;
        reject(new Error(reason));
      }
      for (const [, entry] of pending) entry.reject(new Error('Connection to Yano closed.'));
      pending.clear();
    });
    // The host answers a ping once the wallet-side socket is up; if the wallet
    // isn't running the host exits and onDisconnect fires with the reason.
    try {
      port.postMessage({ id: '__hello__', method: 'isEnabled', origin: 'chrome-extension://self' });
    } catch (e) {
      if (!settled) { settled = true; reject(e); }
    }
  });
}

function sendNative(port, req) {
  return new Promise((resolve, reject) => {
    pending.set(req.id, { resolve, reject });
    const timer = setTimeout(() => {
      if (pending.has(req.id)) {
        pending.delete(req.id);
        reject(new Error('Yano did not respond in time.'));
      }
    }, REQUEST_TIMEOUT_MS);
    const entry = pending.get(req.id);
    const done = entry.resolve;
    entry.resolve = (v) => { clearTimeout(timer); done(v); };
    try {
      port.postMessage(req);
    } catch (e) {
      pending.delete(req.id);
      clearTimeout(timer);
      reject(e);
    }
  });
}

// --- Legacy WebSocket transport (fallback while the host isn't installed) ---

async function walletUrl() {
  // Allow overriding the port from the popup (chrome.storage), default otherwise.
  let port = DEFAULT_PORT;
  try {
    const stored = await chrome.storage.local.get('port');
    if (stored && stored.port) port = stored.port;
  } catch (_) {
    // storage may be unavailable during early SW startup; use the default.
  }
  return 'ws://127.0.0.1:' + port + '/cip30';
}

// One connection attempt. Rejects on error or a 4s open timeout.
function openSocket() {
  return new Promise(async (resolve, reject) => {
    const url = await walletUrl();
    const sock = new WebSocket(url);
    const timer = setTimeout(() => {
      try { sock.close(); } catch (_) {}
      reject(new Error('connect timeout'));
    }, 4000);
    sock.onopen = () => {
      clearTimeout(timer);
      resolve(sock);
    };
    sock.onerror = () => {
      clearTimeout(timer);
      reject(new Error('connect error'));
    };
    sock.onclose = () => {
      if (socket === sock) socket = null;
      // Fail any in-flight requests so callers don't hang.
      for (const [, entry] of pending) entry.reject(new Error('Connection to Yano closed.'));
      pending.clear();
    };
    sock.onmessage = (event) => {
      let data;
      try {
        data = JSON.parse(event.data);
      } catch (_) {
        return;
      }
      const entry = pending.get(data.id);
      if (entry) {
        pending.delete(data.id);
        entry.resolve(data);
      }
    };
  });
}

// Connect with a few quick retries — the MV3 service worker often cold-starts on
// the first request, so the initial WebSocket open can race; retrying makes the
// first connect reliable instead of flaking to "refused".
function connect() {
  if (socket && socket.readyState === WebSocket.OPEN) return Promise.resolve(socket);
  if (connecting) return connecting;
  connecting = (async () => {
    let lastError;
    for (let attempt = 0; attempt < 4; attempt++) {
      try {
        socket = await openSocket();
        return socket;
      } catch (e) {
        lastError = e;
        await new Promise((r) => setTimeout(r, 250 * (attempt + 1)));
      }
    }
    throw new Error('Yano wallet is not reachable — is it running and unlocked?'
      + (lastError ? ' (' + lastError.message + ')' : ''));
  })();
  connecting.finally(() => { connecting = null; }); // let the next request reconnect
  return connecting;
}

function send(sock, req) {
  return new Promise((resolve, reject) => {
    pending.set(req.id, { resolve, reject });
    const timer = setTimeout(() => {
      if (pending.has(req.id)) {
        pending.delete(req.id);
        reject(new Error('Yano did not respond in time.'));
      }
    }, REQUEST_TIMEOUT_MS);
    const entry = pending.get(req.id);
    const done = entry.resolve;
    entry.resolve = (v) => {
      clearTimeout(timer);
      done(v);
    };
    try {
      sock.send(JSON.stringify(req));
    } catch (e) {
      pending.delete(req.id);
      clearTimeout(timer);
      reject(e);
    }
  });
}

// Relay one request over the best available transport: Native Messaging first,
// the legacy WebSocket second. Both speak the same {id,method,params,origin}
// envelope, so callers can't tell which carried their message.
async function relay(req) {
  try {
    const port = await connectNative();
    return await sendNative(port, req);
  } catch (nativeError) {
    try {
      const sock = await connect();
      return await send(sock, req);
    } catch (wsError) {
      throw new Error('Yano wallet is not reachable — is it running and unlocked? ('
        + (wsError && wsError.message ? wsError.message : wsError) + ')');
    }
  }
}

// Reachability probe for the popup: does the wallet answer over the transport a
// dApp would actually use? Native Messaging first, the legacy WebSocket only as a
// fallback — so the popup reflects reality instead of assuming the (opt-in) WS is
// up. Reuses the real connect paths, so a success also warms the connection.
async function probeReachability() {
  try {
    await connectNative();
    return { ok: true, transport: 'native' };
  } catch (nativeError) {
    try {
      await connect();
      return { ok: true, transport: 'ws' };
    } catch (wsError) {
      return { ok: false, error: wsError && wsError.message ? wsError.message : String(wsError) };
    }
  }
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    if (msg && msg.type === '__probe__') {
      sendResponse(await probeReachability());
      return;
    }
    try {
      const reply = await relay({
        id: msg.id,
        method: msg.method,
        params: msg.params,
        origin: msg.origin,
      });
      sendResponse({ result: reply.result, error: reply.error });
    } catch (e) {
      sendResponse({ error: { code: -2, info: e && e.message ? e.message : String(e) } });
    }
  })();
  return true; // keep the message channel open for the async response
});
