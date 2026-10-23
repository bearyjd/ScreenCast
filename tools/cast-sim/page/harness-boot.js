// Injected by the sim (evaluateOnNewDocument) BEFORE receiver.js runs. Tracks
// every RTCPeerConnection receiver.js creates (for getStats) and then loads the
// latency harness once the DOM exists. Does not alter receiver behaviour.
(() => {
  'use strict';
  const Base = window.RTCPeerConnection;
  window.__castSimPcs = [];
  window.RTCPeerConnection = class extends Base {
    constructor(...args) {
      super(...args);
      // Drop closed connections so a long-running page does not keep every PC alive.
      window.__castSimPcs = window.__castSimPcs.filter(pc => pc.connectionState !== 'closed');
      window.__castSimPcs.push(this);
      window.dispatchEvent(new CustomEvent('castsim-newpc'));
    }
  };
  addEventListener('DOMContentLoaded', () => { import('/page/harness.mjs').catch(e => console.error('harness load failed', e)); });
})();
