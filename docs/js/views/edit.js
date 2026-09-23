// Edit any recorded spend: amount, currency, category, date, where, note.
//
// A plain decimal field here rather than the Add screen's keypad. The keypad is
// for speed on a fresh entry; correcting one is usually changing a single digit.

import * as db from '../db.js';
import { format, formatPlain, parseToMinor, symbol, COMMON_CURRENCIES } from '../money.js';
import { esc, qs, qsa, sheet, toast, confirmSheet } from '../ui.js';

export async function openEditSheet(entry, app) {
  const cats = await db.categoriesInOrder();

  sheet((panel, close) => {
    const draft = {
      amount: formatPlain(entry.amountMinor, entry.currency).replace(/,/g, ''),
      currency: entry.currency,
      categoryId: entry.categoryId,
      merchant: entry.merchant || '',
      note: entry.note || '',
      date: entry.date,
    };

    const paint = () => {
      const minor = parseToMinor(draft.amount);
      const valid = minor !== null && minor > 0;
      panel.innerHTML = `
        <div class="row spread">
          <span class="h1">Edit spend</span>
          <span class="muted">${esc(sourceLabel(entry.source))}</span>
        </div>

        <div class="row" style="align-items:flex-end;gap:8px">
          <div class="field grow">
            <label for="amt">Amount</label>
            <input id="amt" inputmode="decimal" class="${valid ? '' : 'bad'}" value="${esc(draft.amount)}">
          </div>
          <button class="btn" data-cur style="margin-bottom:6px">${esc(symbol(draft.currency))} ${esc(draft.currency)}</button>
        </div>
        ${valid ? '' : '<div class="muted" style="color:var(--danger)">Enter an amount above zero</div>'}

        <div class="field">
          <label for="dt">Date</label>
          <input id="dt" type="date" value="${esc(draft.date)}">
        </div>

        <div class="field">
          <label>Category</label>
          <div class="chips">${cats.map((c) => `
            <button class="chip" data-cat="${c.id}" aria-pressed="${c.id === draft.categoryId}">
              ${esc(c.emoji)} ${esc(c.name)}
            </button>`).join('')}</div>
        </div>

        <div class="field">
          <label for="mer">Where</label>
          <input id="mer" value="${esc(draft.merchant)}" placeholder="optional">
        </div>
        <div class="field">
          <label for="note">Note</label>
          <input id="note" value="${esc(draft.note)}" placeholder="optional">
        </div>

        <div class="row spread" style="margin-top:14px">
          <button class="btn danger" data-del>Delete</button>
          <span>
            <button class="btn plain" data-x>Cancel</button>
            <button class="btn" data-ok ${valid ? '' : 'disabled'}>Save</button>
          </span>
        </div>`;

      qs('#amt', panel).oninput = (e) => {
        draft.amount = e.target.value.replace(/[^\d.,]/g, '');
        const cur = parseToMinor(draft.amount);
        // Repaint only when validity flips, so the field does not lose focus
        // or the caret on every keystroke.
        const nowValid = cur !== null && cur > 0;
        if (nowValid !== valid) { paint(); qs('#amt', panel).focus(); }
      };
      qs('#dt', panel).onchange = (e) => { draft.date = e.target.value || draft.date; };
      qs('#mer', panel).oninput = (e) => { draft.merchant = e.target.value; };
      qs('#note', panel).oninput = (e) => { draft.note = e.target.value; };

      qsa('[data-cat]', panel).forEach((b) => {
        b.onclick = () => { draft.categoryId = Number(b.dataset.cat); paint(); };
      });

      qs('[data-cur]', panel).onclick = () => pickCurrency(draft, paint);
      qs('[data-x]', panel).onclick = close;

      qs('[data-del]', panel).onclick = () => {
        confirmSheet({
          title: 'Delete this spend?',
          body: `${format(entry.amountMinor, entry.currency)} on ${draft.date}`,
          confirmLabel: 'Delete',
          danger: true,
          onConfirm: async () => {
            await db.deleteEntry(entry.id);
            close();
            toast('Deleted');
            app.refresh();
          },
        });
      };

      qs('[data-ok]', panel).onclick = async () => {
        const amountMinor = parseToMinor(draft.amount);
        if (amountMinor === null || amountMinor <= 0) return;
        await db.putEntry({
          ...entry,
          amountMinor,
          currency: draft.currency,
          categoryId: draft.categoryId,
          merchant: draft.merchant.trim() || null,
          note: draft.note.trim() || null,
          date: draft.date,
          // dedupeKey is deliberately untouched: it describes the original
          // statement line, so a corrected import is still recognised if the
          // same statement is imported again.
        });
        if (draft.categoryId !== entry.categoryId) await db.bumpCategory(draft.categoryId);
        close();
        toast('Updated');
        app.refresh();
      };
    };

    paint();
  });
}

function pickCurrency(draft, paint) {
  sheet((panel, close) => {
    panel.innerHTML = `<div class="h1">Currency</div>
      <div class="list">${COMMON_CURRENCIES.map((c) => `
        <button class="entry" data-c="${esc(c)}">
          <span class="badge" style="background:var(--surface-3)">${esc(symbol(c))}</span>
          <span class="main"><span class="title">${esc(c)}</span></span>
        </button>`).join('')}</div>`;
    qsa('[data-c]', panel).forEach((b) => {
      b.onclick = () => { draft.currency = b.dataset.c; close(); paint(); };
    });
  });
}

const sourceLabel = (s) => (s === 'STATEMENT' ? 'from a statement' : 'added by hand');
