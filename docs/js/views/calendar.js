// The calendar: a month of spending as a heatmap, with a day's detail below.
//
// Nothing is ever converted between currencies. When a month holds more than
// one, you pick which the grid and totals describe, and the rest are reported
// separately — the app holds no exchange rates and will not invent any.

import * as db from '../db.js';
import { format, formatCompact, symbol } from '../money.js';
import {
  esc, qs, qsa, todayIso, fromIso, toIso, monthKey, monthBounds, shiftMonth,
  fmtMonth, fmtDate,
} from '../ui.js';
import { openEditSheet } from './edit.js';
import { openExportSheet } from './data.js';

const view = { month: monthKey(todayIso()), selected: todayIso(), currency: null };

/** Buckets a day's total into 1..5, or 0 for nothing spent. */
export function heatLevel(totalMinor, maxMinor, levels = 5) {
  if (totalMinor <= 0 || maxMinor <= 0) return 0;
  const ratio = Math.min(1, totalMinor / maxMinor);
  // Square root so one unusual day — rent, a flight — does not flatten every
  // other day to the palest step.
  return Math.min(levels, Math.max(1, Math.ceil(Math.sqrt(ratio) * levels)));
}

export async function renderCalendar(root, app) {
  const [from, to] = monthBounds(view.month);
  const entries = await db.entriesBetween(from, to);
  const cats = new Map((await db.allCategories()).map((c) => [c.id, c]));

  const present = [...new Set(entries.map((e) => e.currency))].sort();
  const shown = view.currency ?? app.baseCurrency;
  const inShown = entries.filter((e) => e.currency === shown);

  const byDay = new Map();
  for (const e of inShown) byDay.set(e.date, (byDay.get(e.date) || 0) + e.amountMinor);
  const maxDay = Math.max(0, ...byDay.values());
  const total = inShown.reduce((n, e) => n + e.amountMinor, 0);

  const others = {};
  for (const e of entries) {
    if (e.currency !== shown) others[e.currency] = (others[e.currency] || 0) + e.amountMinor;
  }

  const activeDays = new Set(entries.map((e) => e.date)).size;
  const perDay = activeDays ? Math.round(total / activeDays) : 0;

  const selectedEntries = view.selected
    ? entries.filter((e) => e.date === view.selected).sort((a, b) => b.createdAt - a.createdAt)
    : [];

  root.innerHTML = `
    <div class="row pad">
      <button class="iconbtn" data-prev aria-label="Previous month">‹</button>
      <div class="grow h1" style="text-align:center">${esc(fmtMonth(view.month))}</div>
      <button class="iconbtn" data-today aria-label="Jump to today">◉</button>
      <button class="iconbtn" data-export aria-label="Export">⤓</button>
      <button class="iconbtn" data-next aria-label="Next month">›</button>
    </div>

    ${present.length > 1 ? `
      <div class="pad" style="padding-top:0">
        <div class="muted">Showing</div>
        <div class="chips">${[...new Set([shown, ...present])].map((c) => `
          <button class="chip" data-cur="${esc(c)}" aria-pressed="${c === shown}">
            ${esc(symbol(c))} ${esc(c)}${c === app.baseCurrency ? ' ·' : ''}
          </button>`).join('')}</div>
      </div>` : ''}

    <div class="stats">
      <div><div class="stat-label">Month total</div><div class="stat-value">${esc(format(total, shown))}</div></div>
      <div><div class="stat-label">Per active day</div><div class="stat-value">${esc(format(perDay, shown))}</div></div>
      <div><div class="stat-label">Days spent</div><div class="stat-value">${activeDays}</div></div>
    </div>
    ${Object.keys(others).length ? `<div class="pad muted" style="padding-top:0">
      Also ${Object.entries(others).map(([c, v]) => esc(format(v, c))).join(', ')} — not converted
    </div>` : ''}

    ${renderGrid(view.month, byDay, maxDay, shown, entries)}

    <div class="legend">
      Less <i style="background:var(--heat-0)"></i>
      ${[1, 2, 3, 4, 5].map((l) => `<i style="background:var(--heat-${l})"></i>`).join('')}
      More${maxDay > 0 ? ` · peak ${esc(formatCompact(maxDay, shown))}` : ''}
    </div>

    <div class="pad h2">${view.selected ? esc(fmtDate(view.selected, { weekday: 'long', day: 'numeric', month: 'long' })) : 'Tap a day'}</div>
    ${selectedEntries.length
      ? selectedEntries.map((e) => entryRow(e, cats)).join('')
      : `<div class="pad muted">${view.selected ? 'Nothing recorded on this day.' : 'Tap a day to see what you spent.'}</div>`}

    ${renderBreakdown(inShown, cats, total, shown)}
    <div style="height:24px"></div>`;

  qs('[data-prev]', root).onclick = () => { view.month = shiftMonth(view.month, -1); app.refresh(); };
  qs('[data-next]', root).onclick = () => { view.month = shiftMonth(view.month, 1); app.refresh(); };
  qs('[data-today]', root).onclick = () => {
    view.month = monthKey(todayIso());
    view.selected = todayIso();
    app.refresh();
  };
  qs('[data-export]', root).onclick = () => openExportSheet(app);

  qsa('[data-cur]', root).forEach((b) => {
    b.onclick = () => {
      view.currency = b.dataset.cur === app.baseCurrency ? null : b.dataset.cur;
      app.refresh();
    };
  });

  qsa('[data-day]', root).forEach((b) => {
    b.onclick = () => {
      view.selected = view.selected === b.dataset.day ? null : b.dataset.day;
      app.refresh();
    };
  });

  qsa('[data-entry]', root).forEach((b) => {
    b.onclick = async () => {
      const entry = await db.getEntry(Number(b.dataset.entry));
      if (entry) openEditSheet(entry, app);
    };
  });
}

function renderGrid(ym, byDay, maxDay, currency, allEntries) {
  const [y, m] = ym.split('-').map(Number);
  const daysInMonth = new Date(y, m, 0).getDate();
  // Which weekday the locale starts on, derived rather than assumed.
  const firstDow = new Date(y, m - 1, 1).getDay();
  const names = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

  const foreignDays = new Set(
    allEntries.filter((e) => e.currency !== currency).map((e) => e.date),
  );

  const cells = [];
  for (let i = 0; i < firstDow; i++) cells.push('<div class="day blank"></div>');
  for (let d = 1; d <= daysInMonth; d++) {
    const iso = `${ym}-${String(d).padStart(2, '0')}`;
    const amount = byDay.get(iso) || 0;
    const level = heatLevel(amount, maxDay);
    const filled = level > 0;
    // Black or white by level, so the day number stays readable on every step.
    const ink = filled && level >= 4 ? 'var(--heat-ink-light)'
      : filled ? 'var(--heat-ink-dark)' : 'var(--text-dim)';
    const bg = filled ? `var(--heat-${level})` : 'var(--heat-0)';
    const classes = ['day'];
    if (iso === todayIso()) classes.push('today');
    if (iso === view.selected) classes.push('sel');
    cells.push(`<button class="${classes.join(' ')}" data-day="${iso}"
        style="background:${bg};color:${ink}">
      <span>${d}</span>
      ${filled ? `<span class="amt">${esc(formatCompact(amount, currency))}</span>`
        : foreignDays.has(iso) ? '<span class="dot"></span>' : ''}
    </button>`);
  }
  while (cells.length % 7 !== 0) cells.push('<div class="day blank"></div>');

  const rows = [];
  for (let i = 0; i < cells.length; i += 7) {
    rows.push(`<div class="weekrow">${cells.slice(i, i + 7).join('')}</div>`);
  }
  return `<div class="weekdays">${names.map((n) => `<span>${n}</span>`).join('')}</div>
          <div class="grid">${rows.join('')}</div>`;
}

function entryRow(e, cats) {
  const c = cats.get(e.categoryId);
  const sub = [
    e.merchant ? c?.name : null,
    e.note,
    e.source === 'STATEMENT' ? 'statement' : null,
  ].filter(Boolean).join(' · ');
  return `<button class="entry" data-entry="${e.id}">
    <span class="badge" style="background:${esc((c?.color || '#90A4AE') + '30')}">${esc(c?.emoji || '➕')}</span>
    <span class="main">
      <span class="title">${esc(e.merchant || c?.name || 'Spend')}</span>
      <span class="sub">${esc(sub)}</span>
    </span>
    <span class="amt">${esc(format(e.amountMinor, e.currency))}</span>
  </button>`;
}

function renderBreakdown(entries, cats, total, currency) {
  if (!entries.length) return '';
  const byCat = new Map();
  for (const e of entries) byCat.set(e.categoryId, (byCat.get(e.categoryId) || 0) + e.amountMinor);
  const rows = [...byCat.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([id, sum]) => {
      const c = cats.get(id);
      const share = total ? sum / total : 0;
      return `<div class="pad" style="padding-top:6px;padding-bottom:6px">
        <div class="row">
          <span>${esc(c?.emoji || '➕')}</span>
          <span class="grow">${esc(c?.name || 'Uncategorised')}</span>
          <span>${esc(format(sum, currency))}</span>
          <span class="muted">${Math.round(share * 100)}%</span>
        </div>
        <div class="bar-track" style="margin-top:4px">
          <div class="bar-fill" style="width:${(share * 100).toFixed(1)}%;background:${esc(c?.color || '#90A4AE')}"></div>
        </div>
      </div>`;
    });
  return `<div class="pad h2">Where it went</div>${rows.join('')}`;
}

export const _internal = { view, fromIso, toIso };
