// Yano CIP-30 bridge — content script (ISOLATED world).
//
// Relays requests from the page's injected provider (inject.js, MAIN world) to
// the extension background service worker, and posts the reply back. The page
// and this script share the DOM window but not variables, so they talk over
// window.postMessage; this script talks to the background over chrome.runtime.
(function () {
  'use strict';

  const NAMESPACE = '__yanoCip30';

  window.addEventListener('message', (event) => {
    if (event.source !== window) return;
    const msg = event.data;
    if (!msg || msg[NAMESPACE] !== true || msg.dir !== 'req') return;

    const reply = (payload) =>
      window.postMessage({ [NAMESPACE]: true, dir: 'res', id: msg.id, ...payload }, window.location.origin);

    chrome.runtime
      .sendMessage({
        method: msg.method,
        params: msg.params,
        id: msg.id,
        origin: window.location.origin,
      })
      .then((res) => {
        if (!res) {
          reply({ error: { code: -2, info: 'Yano connector: no response from background.' } });
        } else {
          reply({ result: res.result, error: res.error });
        }
      })
      .catch((err) => {
        reply({ error: { code: -2, info: 'Yano connector error: ' + (err && err.message ? err.message : err) } });
      });
  });
})();
