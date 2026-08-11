// Popup: shows whether the Yano desktop bridge is reachable, and lets the user
// change the legacy WebSocket fallback port. Reachability is delegated to the
// background service worker so it probes the SAME transport a dApp uses — Native
// Messaging first, the WebSocket only as a fallback — instead of assuming the
// (opt-in) WebSocket is listening.
'use strict';

const DEFAULT_PORT = 27428;
const dot = document.getElementById('dot');
const statusEl = document.getElementById('status');
const viaEl = document.getElementById('via');
const portInput = document.getElementById('port');

function setStatus(kind, text, detail) {
  dot.className = 'dot ' + kind;
  statusEl.textContent = text;
  viaEl.textContent = detail || '';
}

async function currentPort() {
  try {
    const s = await chrome.storage.local.get('port');
    return (s && s.port) || DEFAULT_PORT;
  } catch (_) {
    return DEFAULT_PORT;
  }
}

function probe() {
  setStatus('wait', 'Checking…');
  let settled = false;
  const done = (kind, text, detail) => {
    if (settled) return;
    settled = true;
    setStatus(kind, text, detail);
  };
  // The background worker walks the real transport chain (native, then WS); that
  // can take a couple of seconds when nothing is up, so allow generous time.
  const timer = setTimeout(() => done('bad', 'Wallet not reachable'), 6000);
  try {
    chrome.runtime.sendMessage({ type: '__probe__' }, (res) => {
      clearTimeout(timer);
      if (chrome.runtime.lastError) {
        done('bad', 'Connector error', chrome.runtime.lastError.message);
      } else if (res && res.ok) {
        done('ok', 'Connected to Yano wallet',
          res.transport === 'native' ? 'via Native Messaging' : 'via legacy WebSocket');
      } else {
        done('bad', 'Wallet not running',
          (res && res.error) ? res.error : 'Start and unlock the Yano desktop wallet.');
      }
    });
  } catch (e) {
    clearTimeout(timer);
    done('bad', 'Connector error', e && e.message ? e.message : String(e));
  }
}

(async () => {
  portInput.value = await currentPort();
  probe();
})();

document.getElementById('save').addEventListener('click', async () => {
  const port = parseInt(portInput.value, 10);
  if (!port || port < 1 || port > 65535) {
    setStatus('bad', 'Enter a valid port');
    return;
  }
  try { await chrome.storage.local.set({ port }); } catch (_) {}
  probe();
});
