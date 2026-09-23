import * as db from '../db.js';
import { symbol, COMMON_CURRENCIES } from '../money.js';
import { esc, qs, qsa, sheet, toast, confirmSheet } from '../ui.js';

export async function renderSettings(root, app) {
  const count = (await db.allEntries()).length;
  const standalone = window.matchMedia('(display-mode: standalone)').matches
    || window.navigator.standalone === true;

  root.innerHTML = `
    <div class="pad h1">Settings</div>

    <div class="pad">
      <div class="card">
        <div class="h2">Default currency</div>
        <p class="muted">New entries start here, and the calendar opens on it. You can
        pick a different currency per entry.</p>
        <button class="btn" data-cur>${esc(symbol(app.baseCurrency))} ${esc(app.baseCurrency)}</button>
      </div>

      <div class="card">
        <div class="row spread">
          <div class="grow">
            <div class="h2">One-tap save</div>
            <p class="muted" style="margin:4px 0 0">Tapping a category saves straight away.
            Turn off to press Save yourself.</p>
          </div>
          <input type="checkbox" data-onetap ${app.oneTapSave ? 'checked' : ''}
                 style="width:24px;height:24px">
        </div>
      </div>

      ${standalone ? '' : `
      <div class="card">
        <div class="h2">Add to your Home Screen</div>
        <p class="muted">In Safari, tap the Share button, then <strong>Add to Home
        Screen</strong>. Spendly then opens like an app, full screen and offline.</p>
      </div>`}

      <div class="card">
        <div class="h2">Your data</div>
        <p class="muted">${count} ${count === 1 ? 'entry' : 'entries'}, stored only on this
        device. Nothing is uploaded and there is no account. Export a CSV from the Data
        tab to keep a backup — clearing website data would remove everything here.</p>
        <button class="btn danger" data-wipe>Delete all data</button>
      </div>

      <div class="card">
        <div class="h2">About</div>
        <p class="muted">Spendly is free, has no ads, and collects nothing. If it has made
        tracking your spending a little less of a chore, you are very welcome to
        <a href="https://buymeacoffee.com/casunoxd" target="_blank" rel="noopener">buy me a
        coffee</a> — entirely optional, and using it is honestly enough.</p>
        <p class="muted"><a href="https://github.com/teykaijun/spendly" target="_blank"
        rel="noopener">Source code</a></p>
      </div>

      <div class="card">
        <div class="h2">Why there is no automatic capture here</div>
        <p class="muted">The Android app reads bank notifications and fills entries in by
        itself. iOS gives apps no way to read other apps' notifications, and a web app
        cannot either — so on iPhone the PDF statement import does that job instead.</p>
      </div>
    </div>
    <div style="height:24px"></div>`;

  qs('[data-cur]', root).onclick = () => {
    sheet((panel, close) => {
      panel.innerHTML = `<div class="h1">Default currency</div>
        <div class="list">${COMMON_CURRENCIES.map((c) => `
          <button class="entry" data-c="${esc(c)}">
            <span class="badge" style="background:var(--surface-3)">${esc(symbol(c))}</span>
            <span class="main"><span class="title">${esc(c)}</span></span>
          </button>`).join('')}</div>`;
      qsa('[data-c]', panel).forEach((b) => {
        b.onclick = async () => {
          await db.setSetting('baseCurrency', b.dataset.c);
          close();
          await app.reload();
        };
      });
    });
  };

  qs('[data-onetap]', root).onchange = async (e) => {
    await db.setSetting('oneTapSave', e.target.checked);
    await app.reload();
  };

  qs('[data-wipe]', root).onclick = () => {
    confirmSheet({
      title: 'Delete everything?',
      body: `All ${count} entries and settings on this device. This cannot be undone, and there is no copy anywhere else.`,
      confirmLabel: 'Delete all',
      danger: true,
      onConfirm: async () => {
        await db.clearAll();
        toast('All data deleted');
        await app.reload();
      },
    });
  };
}
