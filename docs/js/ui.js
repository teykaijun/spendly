// Small DOM helpers. No framework: the app is four screens, and a build step
// would make it harder to host and harder to test than it is worth.

/** Escapes text destined for innerHTML. Merchant names come from PDFs. */
export function esc(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export const el = (id) => document.getElementById(id);
export const qs = (sel, root = document) => root.querySelector(sel);
export const qsa = (sel, root = document) => Array.from(root.querySelectorAll(sel));

/** Today as 'YYYY-MM-DD' in the device's own time zone, not UTC. */
export function todayIso() {
  return toIso(new Date());
}

export function toIso(date) {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function fromIso(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function addDays(iso, days) {
  const d = fromIso(iso);
  d.setDate(d.getDate() + days);
  return toIso(d);
}

export function monthKey(iso) {
  return iso.slice(0, 7);
}

export function monthBounds(ym) {
  const [y, m] = ym.split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  return [`${ym}-01`, `${ym}-${String(last).padStart(2, '0')}`];
}

export function shiftMonth(ym, delta) {
  const [y, m] = ym.split('-').map(Number);
  const d = new Date(y, m - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export const fmtDate = (iso, opts = { weekday: 'short', day: 'numeric', month: 'short' }) =>
  fromIso(iso).toLocaleDateString(undefined, opts);

export const fmtMonth = (ym) =>
  new Date(Number(ym.slice(0, 4)), Number(ym.slice(5, 7)) - 1, 1)
    .toLocaleDateString(undefined, { month: 'long', year: 'numeric' });

/** A transient message, optionally with one action (used for Undo). */
export function toast(message, actionLabel, onAction, ms = 4000) {
  const host = el('toast-host');
  host.innerHTML = '';
  const box = document.createElement('div');
  box.className = 'toast';
  box.innerHTML = `<span>${esc(message)}</span>`;
  if (actionLabel) {
    const b = document.createElement('button');
    b.textContent = actionLabel;
    b.onclick = () => { host.innerHTML = ''; onAction?.(); };
    box.appendChild(b);
  }
  host.appendChild(box);
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { if (host.firstChild === box) host.innerHTML = ''; }, ms);
}

/**
 * Opens a bottom sheet. `build` receives the sheet element and a close
 * function. Returns close so callers can dismiss it themselves.
 */
export function sheet(build) {
  const host = el('sheet-host');
  const scrim = document.createElement('div');
  scrim.className = 'scrim';
  const panel = document.createElement('div');
  panel.className = 'sheet';
  scrim.appendChild(panel);

  const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e) => { if (e.key === 'Escape') close(); };

  scrim.addEventListener('click', (e) => { if (e.target === scrim) close(); });
  document.addEventListener('keydown', onKey);

  build(panel, close);
  host.appendChild(scrim);
  return close;
}

/** A yes/no confirmation. Destructive actions must never be one stray tap. */
export function confirmSheet({ title, body, confirmLabel = 'Confirm', danger = false, onConfirm }) {
  sheet((panel, close) => {
    panel.innerHTML = `
      <div class="h1">${esc(title)}</div>
      <p class="muted">${esc(body)}</p>
      <div class="row spread" style="margin-top:14px">
        <button class="btn plain" data-x>Cancel</button>
        <button class="btn ${danger ? 'danger' : ''}" data-ok>${esc(confirmLabel)}</button>
      </div>`;
    qs('[data-x]', panel).onclick = close;
    qs('[data-ok]', panel).onclick = () => { close(); onConfirm(); };
  });
}
