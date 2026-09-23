// App shell: loads settings, renders the current tab, and keeps the rest of the
// code free of routing concerns.

import * as db from './db.js';
import { el, qsa } from './ui.js';
import { renderAdd } from './views/add.js';
import { renderCalendar } from './views/calendar.js';
import { renderData } from './views/data.js';
import { renderSettings } from './views/settings.js';

const VIEWS = {
  add: renderAdd,
  calendar: renderCalendar,
  data: renderData,
  settings: renderSettings,
};

const app = {
  tab: 'add',
  baseCurrency: 'MYR',
  oneTapSave: true,

  /** Re-reads settings from storage, then repaints. */
  async reload() {
    app.baseCurrency = await db.getSetting('baseCurrency', guessCurrency());
    app.oneTapSave = await db.getSetting('oneTapSave', true);
    await app.refresh();
  },

  /**
   * Repaints the current tab.
   *
   * Rendering reads the database, so it is asynchronous, and typing on the
   * keypad fires one refresh per tap. Without coalescing, two renders overlap
   * and whichever finishes last wins — which is not necessarily the newest
   * state, so a fast typist sees a stale amount. Bursts collapse into a single
   * trailing render instead.
   */
  async refresh() {
    if (app._rendering) { app._dirty = true; return; }
    app._rendering = true;
    try {
      const root = el('view');
      do {
        app._dirty = false;
        await VIEWS[app.tab](root, app);
      } while (app._dirty);
      qsa('.tab').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === app.tab)));
    } finally {
      app._rendering = false;
    }
  },

  _rendering: false,
  _dirty: false,

  async go(tab) {
    if (!VIEWS[tab]) return;
    app.tab = tab;
    el('view').scrollTop = 0;
    await app.refresh();
  },
};

/** The device's currency, so the first run is usually already correct. */
function guessCurrency() {
  try {
    const region = new Intl.Locale(navigator.language).maximize().region;
    const byRegion = {
      MY: 'MYR', SG: 'SGD', US: 'USD', GB: 'GBP', AU: 'AUD', NZ: 'NZD',
      CA: 'CAD', HK: 'HKD', TW: 'TWD', JP: 'JPY', CN: 'CNY', KR: 'KRW',
      IN: 'INR', ID: 'IDR', TH: 'THB', PH: 'PHP', VN: 'VND', BR: 'BRL',
      ZA: 'ZAR', CH: 'CHF', SE: 'SEK', NO: 'NOK', DK: 'DKK', PL: 'PLN',
      TR: 'TRY', AE: 'AED', SA: 'SAR', NG: 'NGN', RU: 'RUB', PK: 'PKR',
    };
    if (byRegion[region]) return byRegion[region];
    const euro = ['DE', 'FR', 'ES', 'IT', 'NL', 'BE', 'AT', 'IE', 'PT', 'FI', 'GR'];
    if (euro.includes(region)) return 'EUR';
  } catch { /* fall through */ }
  return 'USD';
}

qsa('.tab').forEach((b) => { b.onclick = () => app.go(b.dataset.tab); });

// Stop the whole page rubber-banding when a scrollable area is already at its
// end — without this, iOS drags the app itself and reveals the background.
document.addEventListener('touchmove', (e) => {
  if (!e.target.closest('.view, .list, .sheet, .catstrip')) e.preventDefault();
}, { passive: false });

app.reload().catch((e) => {
  el('view').innerHTML =
    `<div class="empty"><p>Spendly could not open its storage.</p>
     <p class="muted">${String(e?.message || e)}</p>
     <p class="muted">Private Browsing blocks storage on iOS — try a normal tab.</p></div>`;
});

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js').catch(() => { /* offline is optional */ });
  });
}
