// The Add screen: amount, category, done.
//
// Digits fill from the right the way an ATM keypad does — "1 2 5 0" reads as
// 12.50 — because the decimal point is the most common source of mis-keyed
// amounts, and removing it takes a keystroke out of every entry.

import * as db from '../db.js';
import { format, decimals, symbol, COMMON_CURRENCIES } from '../money.js';
import {
  esc, qs, qsa, sheet, toast, todayIso, addDays, fmtDate, fromIso, toIso,
} from '../ui.js';
import { openEditSheet } from './edit.js';

const state = {
  digits: '',
  categoryId: null,
  date: todayIso(),
  currency: null, // null = follow the default
};

export async function renderAdd(root, app) {
  const base = app.baseCurrency;
  const active = state.currency ?? base;
  const cats = await db.allCategories();
  const dayEntries = (await db.entriesOn(state.date))
    .sort((a, b) => b.createdAt - a.createdAt);
  const dayTotal = dayEntries
    .filter((e) => e.currency === base)
    .reduce((n, e) => n + e.amountMinor, 0);

  const amountMinor = toMinor(state.digits, active);
  const showList = amountMinor === 0 && dayEntries.length > 0;

  root.innerHTML = `
    <div class="add-screen">
      <div class="datestrip">
        <span class="day">${esc(dayLabel(state.date))}</span>
        <span class="total">${esc(format(dayTotal, base))} so far</span>
      </div>
      <div class="chips">
        <button class="chip" data-d="today" aria-pressed="${state.date === todayIso()}">Today</button>
        <button class="chip" data-d="yesterday" aria-pressed="${state.date === addDays(todayIso(), -1)}">Yesterday</button>
        <button class="chip" data-d="pick">${esc(pickLabel(state.date))}</button>
      </div>

      <div class="amount-area">
        ${showList ? renderDayList(dayEntries, cats) : `
          <button class="currency-chip ${active === base ? '' : 'foreign'}" data-cur>
            ${esc(symbol(active))}&nbsp; ${esc(active)} ▾
          </button>
          <div class="amount ${amountMinor === 0 ? 'zero' : ''}">${esc(format(amountMinor, active))}</div>
        `}
      </div>

      <div class="catstrip">
        ${cats.map((c) => `
          <button class="cat" data-cat="${c.id}"
            aria-pressed="${state.categoryId === c.id}"
            style="${state.categoryId === c.id ? `background:${esc(c.color)}` : ''}"
            ${amountMinor > 0 ? '' : 'disabled'}>
            ${esc(c.emoji)} ${esc(c.name)}
          </button>`).join('')}
      </div>

      <div class="keypad">
        ${['1', '2', '3', '4', '5', '6', '7', '8', '9', '00', '0', '⌫']
          .map((k) => `<button class="key" data-k="${esc(k)}">${esc(k)}</button>`).join('')}
      </div>

      ${amountMinor > 0 && state.categoryId === null && app.oneTapSave
        ? '<div class="hint">Tap a category to save</div>' : ''}
      <button class="primary-btn" data-save ${amountMinor > 0 ? '' : 'disabled'}>Save</button>
    </div>`;

  // ---- wiring ----

  qsa('[data-k]', root).forEach((b) => {
    b.onclick = () => {
      const k = b.dataset.k;
      if (k === '⌫') state.digits = state.digits.slice(0, -1);
      else if (k === '00') { if (state.digits) state.digits = trim(state.digits + '00'); }
      else state.digits = trim(state.digits + k);
      navigator.vibrate?.(8);
      app.refresh();
    };
  });

  qsa('[data-cat]', root).forEach((b) => {
    b.onclick = async () => {
      state.categoryId = Number(b.dataset.cat);
      if (app.oneTapSave && toMinor(state.digits, active) > 0) await save(app, active);
      else app.refresh();
    };
  });

  qs('[data-save]', root)?.addEventListener('click', () => save(app, active));

  qsa('[data-d]', root).forEach((b) => {
    b.onclick = () => {
      const which = b.dataset.d;
      if (which === 'today') state.date = todayIso();
      else if (which === 'yesterday') state.date = addDays(todayIso(), -1);
      else return pickDate(app);
      app.refresh();
    };
  });

  qs('[data-cur]', root)?.addEventListener('click', () => pickCurrency(app, base));

  qsa('[data-entry]', root).forEach((b) => {
    b.onclick = async () => {
      const entry = await db.getEntry(Number(b.dataset.entry));
      if (entry) openEditSheet(entry, app);
    };
  });
}

// ---------------------------------------------------------------- helpers --

/** Keypad digits to stored minor units. */
function toMinor(digits, currency) {
  const raw = Number(digits || '0');
  if (!Number.isFinite(raw)) return 0;
  // Currencies with no minor unit (JPY, KRW, VND) take each keypress as a
  // whole unit, so they scale up to match the stored x100 convention.
  return decimals(currency) === 0 ? raw * 100 : raw;
}

const trim = (s) => s.replace(/^0+/, '').slice(0, 10);

const dayLabel = (iso) =>
  iso === todayIso() ? 'Today'
    : iso === addDays(todayIso(), -1) ? 'Yesterday'
      : fmtDate(iso);

const pickLabel = (iso) =>
  (iso === todayIso() || iso === addDays(todayIso(), -1)) ? 'Pick date' : fmtDate(iso);

function renderDayList(entries, cats) {
  const byId = new Map(cats.map((c) => [c.id, c]));
  return `<div class="list" style="width:100%">${entries.map((e) => {
    const c = byId.get(e.categoryId);
    return `<button class="entry" data-entry="${e.id}">
      <span class="badge" style="background:${esc((c?.color || '#90A4AE') + '30')}">${esc(c?.emoji || '➕')}</span>
      <span class="main">
        <span class="title">${esc(e.merchant || c?.name || 'Spend')}</span>
        <span class="sub">${esc([
          e.merchant ? c?.name : null,
          e.note,
          e.source === 'STATEMENT' ? 'statement' : null,
        ].filter(Boolean).join(' · '))}</span>
      </span>
      <span class="amt">${esc(format(e.amountMinor, e.currency))}</span>
    </button>`;
  }).join('')}</div>`;
}

async function save(app, currency) {
  const amountMinor = toMinor(state.digits, currency);
  if (amountMinor <= 0) return;
  const categoryId = state.categoryId ?? (await db.allCategories())[0]?.id;
  if (!categoryId) return;

  const id = await db.addEntry({
    amountMinor,
    currency,
    categoryId,
    merchant: null,
    note: null,
    date: state.date,
    source: db.SOURCE.MANUAL,
    dedupeKey: null,
  });
  await db.bumpCategory(categoryId);

  const cats = await db.allCategories();
  const name = cats.find((c) => c.id === categoryId)?.name ?? '';
  toast(`Saved ${format(amountMinor, currency)}${name ? ` · ${name}` : ''}`, 'Undo', async () => {
    await db.deleteEntry(id);
    app.refresh();
  });

  // Keep the date and the currency — several entries for one day, and several
  // in a row in one currency when travelling, are both normal.
  state.digits = '';
  state.categoryId = null;
  navigator.vibrate?.(18);
  app.refresh();
}

function pickDate(app) {
  sheet((panel, close) => {
    panel.innerHTML = `
      <div class="h1">Pick a date</div>
      <div class="field"><input type="date" value="${esc(state.date)}" max="${esc(todayIso())}"></div>
      <div class="row spread"><button class="btn plain" data-x>Cancel</button>
      <button class="btn" data-ok>Use date</button></div>`;
    const input = qs('input', panel);
    qs('[data-x]', panel).onclick = close;
    qs('[data-ok]', panel).onclick = () => {
      if (input.value) state.date = input.value;
      close();
      app.refresh();
    };
  });
}

function pickCurrency(app, base) {
  sheet((panel, close) => {
    panel.innerHTML = `
      <div class="h1">Currency for this entry</div>
      <p class="muted">Stays selected until you change it back.</p>
      <div class="list">${COMMON_CURRENCIES.map((c) => `
        <button class="entry" data-c="${esc(c)}">
          <span class="badge" style="background:var(--surface-3)">${esc(symbol(c))}</span>
          <span class="main"><span class="title">${esc(c)}</span>
          ${c === base ? '<span class="sub">default</span>' : ''}</span>
        </button>`).join('')}</div>`;
    qsa('[data-c]', panel).forEach((b) => {
      b.onclick = () => {
        const code = b.dataset.c;
        state.currency = code === base ? null : code;
        close();
        app.refresh();
      };
    });
  });
}

export const _internal = { toMinor, state, fromIso, toIso };
