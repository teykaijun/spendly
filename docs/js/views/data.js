// The Data screen: import a PDF statement, filter a date range, export CSV.

import * as db from '../db.js';
import { format, parseToMinor } from '../money.js';
import { parseStatement, detectYear, spendsOf, skippedOf } from '../statement.js';
import { extractPdfText, PdfError } from '../pdf.js';
import {
  esc, qs, qsa, sheet, toast, todayIso, addDays, monthBounds, monthKey, shiftMonth, fmtDate,
} from '../ui.js';

export async function renderData(root, app) {
  root.innerHTML = `
    <div class="pad h1">Data</div>

    <div class="pad">
      <div class="card">
        <div class="h2">Import a PDF statement</div>
        <p class="muted">Read spending out of a bank or e-wallet statement. The file is
        opened on this device and never uploaded. Every row is shown for review first,
        and reloads are left out — moving money into a wallet is not spending.</p>
        <button class="btn" data-import>Choose PDF</button>
        <input type="file" accept="application/pdf" hidden data-file>
      </div>

      <div class="card">
        <div class="h2">Export spending</div>
        <p class="muted">Pick a date range, see what is in it, then save or share it
        as a spreadsheet. This is also your backup — the app stores everything on
        this device only.</p>
        <button class="btn" data-export>Export CSV</button>
      </div>
    </div>`;

  const fileInput = qs('[data-file]', root);
  qs('[data-import]', root).onclick = () => fileInput.click();
  fileInput.onchange = async () => {
    const file = fileInput.files?.[0];
    fileInput.value = '';
    if (file) await openImportSheet(file, app);
  };
  qs('[data-export]', root).onclick = () => openExportSheet(app);
}

// ------------------------------------------------------------------ export --

const PRESETS = {
  'This month': () => monthBounds(monthKey(todayIso())),
  'Last month': () => monthBounds(shiftMonth(monthKey(todayIso()), -1)),
  'Last 30 days': () => [addDays(todayIso(), -29), todayIso()],
  'This year': () => [`${todayIso().slice(0, 4)}-01-01`, `${todayIso().slice(0, 4)}-12-31`],
  Everything: () => ['0000-01-01', '9999-12-31'],
};

export function openExportSheet(app) {
  let preset = 'This month';
  let [from, to] = PRESETS[preset]();

  sheet((panel, close) => {
    const paint = async () => {
      const rows = (await db.entriesBetween(from, to))
        .sort((a, b) => (a.date < b.date ? 1 : -1));
      const totals = {};
      for (const e of rows) totals[e.currency] = (totals[e.currency] || 0) + e.amountMinor;

      panel.innerHTML = `
        <div class="h1">Spending by date range</div>
        <div class="chips">${Object.keys(PRESETS).concat('Custom').map((p) => `
          <button class="chip" data-p="${esc(p)}" aria-pressed="${p === preset}">${esc(p)}</button>`).join('')}
        </div>
        ${preset === 'Custom' ? `
          <div class="row">
            <div class="field grow"><label>From</label><input type="date" data-from value="${esc(from)}"></div>
            <div class="field grow"><label>To</label><input type="date" data-to value="${esc(to)}"></div>
          </div>` : `<div class="muted">${esc(fmtDate(from))} – ${esc(fmtDate(to))}</div>`}

        ${Object.keys(totals).length
          ? Object.entries(totals).sort((a, b) => b[1] - a[1]).map(([c, v]) =>
            `<div class="row spread"><span>${esc(c)}</span><span class="h2">${esc(format(v, c))}</span></div>`).join('')
          : '<p class="muted">No spending in this range.</p>'}
        <div class="muted">${rows.length} ${rows.length === 1 ? 'entry' : 'entries'}</div>

        <button class="primary-btn" data-go ${rows.length ? '' : 'disabled'} style="margin-top:12px">Export CSV</button>
        <div class="list">${rows.slice(0, 60).map((e) => `
          <div class="row spread" style="padding:6px 0;border-bottom:1px solid var(--border)">
            <span class="grow">${esc(e.merchant || '—')}<br><span class="muted">${esc(e.date)}</span></span>
            <span>${esc(format(e.amountMinor, e.currency))}</span>
          </div>`).join('')}</div>`;

      qsa('[data-p]', panel).forEach((b) => {
        b.onclick = () => {
          preset = b.dataset.p;
          if (preset !== 'Custom') [from, to] = PRESETS[preset]();
          paint();
        };
      });
      qs('[data-from]', panel)?.addEventListener('change', (e) => {
        from = e.target.value;
        if (to < from) to = from;
        paint();
      });
      qs('[data-to]', panel)?.addEventListener('change', (e) => {
        to = e.target.value;
        if (to < from) from = to;
        paint();
      });
      qs('[data-go]', panel).onclick = async () => {
        await exportCsv(rows, from, to);
        close();
      };
    };
    paint();
  });
  void app;
}

function csvCell(value) {
  const s = String(value ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

async function exportCsv(rows, from, to) {
  const cats = new Map((await db.allCategories()).map((c) => [c.id, c.name]));
  const lines = ['date,amount,currency,category,merchant,note,source'];
  for (const e of rows.slice().sort((a, b) => (a.date > b.date ? 1 : -1))) {
    lines.push([
      e.date,
      (e.amountMinor / 100).toFixed(2),
      e.currency,
      cats.get(e.categoryId) || '',
      e.merchant || '',
      e.note || '',
      e.source || 'MANUAL',
    ].map(csvCell).join(','));
  }
  const name = `spendly-${from}_to_${to}.csv`;
  const blob = new Blob([lines.join('\n')], { type: 'text/csv' });
  const file = new File([blob], name, { type: 'text/csv' });

  // The share sheet is the only way to get a file into Files or Mail on iOS.
  // A download link works everywhere else and as the fallback.
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: 'Spendly export' });
      return;
    } catch (e) {
      if (e?.name === 'AbortError') return;
    }
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
  toast('Saved to your downloads');
}

// ------------------------------------------------------------------ import --

export async function openImportSheet(file, app) {
  const close = sheet((panel) => {
    panel.innerHTML = '<div class="h1">Reading the statement…</div><p class="muted">This can take a moment for a long PDF.</p>';
  });

  let text;
  try {
    text = await extractPdfText(file, async () => askPassword());
  } catch (e) {
    close();
    const message = e instanceof PdfError ? e.message : 'That file could not be read.';
    sheet((panel, c) => {
      panel.innerHTML = `<div class="h1">Could not read it</div>
        <div class="card warn"><p>${esc(message)}</p></div>
        <button class="btn" data-x>Close</button>`;
      qs('[data-x]', panel).onclick = c;
    });
    return;
  }

  close();

  const base = app.baseCurrency;
  const result = parseStatement(text, base, detectYear(text) ?? new Date().getFullYear());
  const spends = spendsOf(result);
  const skipped = skippedOf(result);

  if (!spends.length && !skipped.length) {
    sheet((panel, c) => {
      panel.innerHTML = `<div class="h1">Nothing recognised</div>
        <p class="muted">No transactions could be read from that statement. The layout
        may be one Spendly does not know yet.</p>
        <button class="btn" data-x>Close</button>`;
      qs('[data-x]', panel).onclick = c;
    });
    return;
  }

  const cats = await db.categoriesInOrder();
  const fallback = cats.find((c) => c.name === 'Other') ?? cats[0];

  const rows = [];
  for (const r of spends) {
    const key = dedupeKey(r);
    const dup = await db.hasDedupeKey(key);
    rows.push({ ...r, key, selected: !dup, duplicate: dup, categoryId: fallback.id });
  }

  sheet((panel, closeReview) => {
    const paint = () => {
      const chosen = rows.filter((r) => r.selected);
      const total = chosen.reduce((n, r) => n + r.amountMinor, 0);
      panel.innerHTML = `
        <div class="h1">Review ${rows.length} ${rows.length === 1 ? 'spend' : 'spends'}</div>
        <div class="muted">${esc(file.name)} · ${chosen.length} selected · ${esc(format(total, result.currency))}</div>
        ${rows.some((r) => r.duplicate)
          ? `<div class="muted">${rows.filter((r) => r.duplicate).length} already imported — left unticked.</div>` : ''}
        <div class="row"><button class="btn plain" data-all>Select all</button>
          <button class="btn plain" data-none>None</button></div>
        <div class="list">${rows.map((r, i) => `
          <label class="check">
            <input type="checkbox" data-i="${i}" ${r.selected ? 'checked' : ''}>
            <span class="grow">${esc(r.description)}<br>
              <span class="muted">${esc(r.date)}</span></span>
            <span>${esc(format(r.amountMinor, r.currency))}</span>
          </label>`).join('')}</div>

        ${skipped.length ? `<details style="margin-top:10px">
          <summary class="muted">${skipped.length} left out (reloads and money in)</summary>
          ${skipped.map((s) => `<div class="row spread" style="padding:5px 0">
            <span class="grow">${esc(s.description)}<br><span class="muted">${esc(s.skipReason || '')}</span></span>
            <span class="muted">${esc(format(s.amountMinor, s.currency))}</span>
          </div>`).join('')}
        </details>` : ''}

        <button class="primary-btn" data-go ${chosen.length ? '' : 'disabled'} style="margin-top:12px">
          ${chosen.length ? `Import ${chosen.length}` : 'Nothing selected'}
        </button>
        <button class="btn plain" data-x style="width:100%">Cancel</button>`;

      qsa('[data-i]', panel).forEach((cb) => {
        cb.onchange = () => { rows[Number(cb.dataset.i)].selected = cb.checked; paint(); };
      });
      qs('[data-all]', panel).onclick = () => { rows.forEach((r) => { r.selected = true; }); paint(); };
      qs('[data-none]', panel).onclick = () => { rows.forEach((r) => { r.selected = false; }); paint(); };
      qs('[data-x]', panel).onclick = closeReview;
      qs('[data-go]', panel).onclick = async () => {
        let n = 0;
        for (const r of rows.filter((x) => x.selected)) {
          await db.addEntry({
            amountMinor: r.amountMinor,
            currency: r.currency,
            categoryId: r.categoryId,
            merchant: r.description.slice(0, 60),
            note: null,
            date: r.date,
            source: db.SOURCE.STATEMENT,
            dedupeKey: r.key,
          });
          n++;
        }
        closeReview();
        toast(`Imported ${n} ${n === 1 ? 'spend' : 'spends'}`);
        app.refresh();
      };
    };
    paint();
  });
}

const dedupeKey = (r) =>
  `statement|${r.date}|${r.amountMinor}|${r.currency}|${r.description.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()}`;

function askPassword() {
  return new Promise((resolve) => {
    sheet((panel, close) => {
      panel.innerHTML = `<div class="h1">Password needed</div>
        <p class="muted">That statement is protected. Banks usually use your IC number,
        passport number or date of birth. It is used to open the file and is not stored.</p>
        <div class="field"><input type="password" data-pw autocomplete="off"></div>
        <div class="row spread">
          <button class="btn plain" data-x>Cancel</button>
          <button class="btn" data-ok>Unlock</button>
        </div>`;
      const input = qs('[data-pw]', panel);
      qs('[data-x]', panel).onclick = () => { close(); resolve(null); };
      qs('[data-ok]', panel).onclick = () => { close(); resolve(input.value); };
      setTimeout(() => input.focus(), 50);
    });
  });
}

export const _internal = { csvCell, dedupeKey, parseToMinor };
