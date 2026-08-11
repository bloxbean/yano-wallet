// Yano CIP-30 provider — injected into the page's own JS context (MAIN world).
//
// Defines window.cardano.yano per CIP-30. Every call is forwarded to the
// isolated content script (content.js) via window.postMessage, which relays to
// the extension background and on to the running Yano desktop app. This script
// holds NO keys and makes NO decisions — it is a typed shell over a message bus.
(function () {
  'use strict';

  const NAMESPACE = '__yanoCip30';
  const WALLET_KEY = 'yano';
  const API_VERSION = '0.1.0';
  // Small inline mark so the dApp's wallet picker can show an icon.
  const ICON =
    'data:image/svg+xml;base64,' +
    btoa(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' +
        '<rect width="24" height="24" rx="5" fill="#1f2547"/>' +
        '<path d="M6 15l6-6 6 6-6-2-6 2z" fill="#6ea8ff"/>' +
      '</svg>'
    );

  const pending = new Map();
  let seq = 0;

  window.addEventListener('message', (event) => {
    // Only trust messages from this same window, tagged as our responses.
    if (event.source !== window) return;
    const msg = event.data;
    if (!msg || msg[NAMESPACE] !== true || msg.dir !== 'res') return;
    const entry = pending.get(msg.id);
    if (!entry) return;
    pending.delete(msg.id);
    if (msg.error) {
      entry.reject(msg.error); // CIP-30 APIError/TxSignError shape { code, info }
    } else {
      entry.resolve(msg.result);
    }
  });

  function request(method, params) {
    return new Promise((resolve, reject) => {
      const id = WALLET_KEY + ':' + ++seq;
      pending.set(id, { resolve, reject });
      window.postMessage(
        { [NAMESPACE]: true, dir: 'req', id, method, params: params || {} },
        window.location.origin
      );
    });
  }

  // The full API object returned by enable(). Paginate/amount args are passed
  // through verbatim; the desktop bridge does the real work.
  const fullApi = {
    getNetworkId: () => request('getNetworkId'),
    getExtensions: () => request('getExtensions'),
    getUtxos: (amount, paginate) => request('getUtxos', { amount, paginate }),
    getCollateral: (params) => request('getCollateral', { params }),
    getBalance: () => request('getBalance'),
    getUsedAddresses: (paginate) => request('getUsedAddresses', { paginate }),
    getUnusedAddresses: () => request('getUnusedAddresses'),
    getChangeAddress: () => request('getChangeAddress'),
    getRewardAddresses: () => request('getRewardAddresses'),
    signTx: (tx, partialSign) => request('signTx', { tx, partialSign: !!partialSign }),
    signData: (addr, payload) => request('signData', { addr, payload }),
    submitTx: (tx) => request('submitTx', { tx }),
  };

  const provider = {
    apiVersion: API_VERSION,
    name: 'Yano',
    icon: ICON,
    supportedExtensions: [], // CIP-95 governance advertised here in a later milestone

    isEnabled: () => request('isEnabled').then((v) => !!v),

    enable: async () => {
      const granted = await request('enable');
      if (!granted) {
        throw { code: -3, info: 'The user declined to connect Yano to this site.' }; // APIError.Refused
      }
      return fullApi;
    },
  };

  const cardano = (window.cardano = window.cardano || {});
  // Don't clobber an already-injected instance (e.g. duplicate injection).
  if (!cardano[WALLET_KEY]) {
    // enumerable:true is REQUIRED for discovery — ecosystem wallet pickers
    // (CF cardano-connect-with-wallet, Mesh, …) enumerate window.cardano via
    // Object.keys(), which only yields enumerable own properties. Without it the
    // wallet is reachable by name but invisible to every generic picker.
    // writable/configurable stay false; enumerability is independent of both, so
    // the anti-tamper posture is unchanged.
    Object.defineProperty(cardano, WALLET_KEY, {
      value: provider,
      enumerable: true,
      writable: false,
      configurable: false,
    });
  }
})();
