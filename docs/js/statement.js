// Turns the text of a bank or e-wallet statement into candidate spends.
// Ported from StatementParser.kt, including its heuristics and its limits.
//
// Statement layouts are not standardised in any useful sense — every bank
// invents its own column order, date format and debit marker — so this is
// frankly heuristic and the import screen always shows what it found for
// review. Nothing is ever written without confirmation.

import { parseToMinor } from './money.js';
import { isTransfer, transferReason } from './transfer.js';

export const Kind = { SPEND: 'SPEND', CREDIT: 'CREDIT', TRANSFER: 'TRANSFER' };

const MONTHS = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6,
  jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12,
};

// Most specific first. Each entry returns {y, m, d} or null.
const DATE_PATTERNS = [
  [/\b(\d{4})-(\d{2})-(\d{2})\b/, (m) => ({ y: +m[1], m: +m[2], d: +m[3] })],
  [/\b(\d{2})\/(\d{2})\/(\d{4})\b/, (m) => ({ y: +m[3], m: +m[2], d: +m[1] })],
  [/\b(\d{2})-(\d{2})-(\d{4})\b/, (m) => ({ y: +m[3], m: +m[2], d: +m[1] })],
  [/\b(\d{2})\.(\d{2})\.(\d{4})\b/, (m) => ({ y: +m[3], m: +m[2], d: +m[1] })],
  [/\b(\d{2})\/(\d{2})\/(\d{2})\b/, (m) => ({ y: 2000 + +m[3], m: +m[2], d: +m[1] })],
  [/\b(\d{2})-(\d{2})-(\d{2})\b/, (m) => ({ y: 2000 + +m[3], m: +m[2], d: +m[1] })],
  [/\b(\d{1,2}) ([A-Za-z]{3,9}) (\d{4})\b/, (m) => monthName(m[2], +m[1], +m[3])],
  [/\b([A-Za-z]{3,9}) (\d{1,2}),? (\d{4})\b/, (m) => monthName(m[1], +m[2], +m[3])],
  // Year-less, e.g. "01 FEB" — resolved against the statement's own year.
  [/\b(\d{1,2}) ([A-Za-z]{3})\b/, (m, fallbackYear) => monthName(m[2], +m[1], fallbackYear)],
];

function monthName(name, day, year) {
  const mm = MONTHS[name.slice(0, 3).toLowerCase()];
  if (!mm || !year) return null;
  return { y: year, m: mm, d: day };
}

function toIsoDate(parts) {
  if (!parts) return null;
  const { y, m, d } = parts;
  if (m < 1 || m > 12 || d < 1 || d > 31) return null;
  const date = new Date(Date.UTC(y, m - 1, d));
  if (date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null;
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/** Finds a date and the span it occupied, so it can be stripped from the text. */
function findDate(line, fallbackYear) {
  for (const [regex, build] of DATE_PATTERNS) {
    const m = regex.exec(line);
    if (!m) continue;
    const iso = toIsoDate(build(m, fallbackYear));
    if (iso) return { date: iso, start: m.index, end: m.index + m[0].length };
  }
  return null;
}

/**
 * A money-shaped number on a statement line. Requires decimals or a thousands
 * separator, so bare integers — card fragments, reference numbers, page
 * numbers — are not mistaken for amounts.
 */
const AMOUNT = /(?<![\d.,])([-+]?)\s?(\d{1,3}(?:,\d{3})+(?:\.\d{2})?|\d+\.\d{2})\s?(CR|DR|-|\+)?(?!\d)/gi;

function findAmounts(line) {
  const hits = [];
  AMOUNT.lastIndex = 0;
  let m;
  while ((m = AMOUNT.exec(line)) !== null) {
    const minor = parseToMinor(m[2]);
    if (minor === null || minor <= 0) continue;
    const sign = m[1];
    const marker = (m[3] || '').toUpperCase();
    let isCredit = null;
    if (marker === 'CR' || sign === '+' || marker === '+') isCredit = true;
    else if (marker === 'DR' || sign === '-' || marker === '-') isCredit = false;
    hits.push({ minor, start: m.index, end: m.index + m[0].length, isCredit });
  }
  return hits;
}

const CREDIT_WORDS = [
  'salary', 'payroll', 'refund', 'reversal', 'reversed', 'cashback',
  'rebate', 'interest', 'dividend', 'received', 'incoming', 'credit',
  'deposit', 'repayment received', 'claim',
];

const DEBIT_WORDS = [
  'purchase', 'payment', 'paid', 'pos', 'debit', 'withdrawal', 'atm',
  'fee', 'charge', 'subscription', 'bill', 'insurance', 'transfer to',
  'duitnow', 'spending',
];

function looksLikeCredit(description) {
  const d = description.toLowerCase();
  const credit = CREDIT_WORDS.filter((w) => d.includes(w)).length;
  const debit = DEBIT_WORDS.filter((w) => d.includes(w)).length;
  return credit > debit;
}

const NOISE = [
  'opening balance', 'closing balance', 'balance b/f', 'balance c/f',
  'brought forward', 'carried forward', 'total', 'subtotal', 'statement',
  'page ', 'account number', 'account no', 'summary', 'minimum payment',
  'credit limit', 'available balance', 'statement date', 'due date',
];

function isNoise(line) {
  const l = line.toLowerCase();
  return NOISE.some((n) => l.includes(n));
}

/** Everything on the line that is not the date or an amount. */
function describe(line, dateSpan, amounts) {
  const drop = new Array(line.length).fill(false);
  for (let i = dateSpan.start; i < dateSpan.end; i++) drop[i] = true;
  for (const a of amounts) for (let i = a.start; i < a.end; i++) drop[i] = true;

  let out = '';
  for (let i = 0; i < line.length; i++) if (!drop[i]) out += line[i];

  out = out.replace(/[|;]+/g, ' ').replace(/\s{2,}/g, ' ').trim()
    .replace(/^[-.,:]+|[-.,:]+$/g, '').trim();
  return out.length > 80 ? out.slice(0, 80).trim() : out;
}

/** The statement's currency, from whatever symbol or code appears in it. */
function detectCurrency(text, baseCurrency) {
  const head = text.slice(0, 4000);
  const sym = /\b(RM|S\$|US\$|HK\$|NT\$|A\$|C\$|NZ\$|R\$|Rp)\s?\d/.exec(head);
  if (sym) {
    const map = {
      RM: 'MYR', 'S$': 'SGD', 'US$': 'USD', 'HK$': 'HKD', 'NT$': 'TWD',
      'A$': 'AUD', 'C$': 'CAD', 'NZ$': 'NZD', 'R$': 'BRL', Rp: 'IDR',
    };
    if (map[sym[1]]) return map[sym[1]];
  }
  const code = /\b(MYR|SGD|USD|EUR|GBP|THB|IDR|PHP|VND|JPY|CNY|INR|AUD|HKD)\b/.exec(head);
  return code ? code[1] : baseCurrency;
}

/** Best guess at the year a statement covers, for year-less dates. */
export function detectYear(text) {
  const counts = new Map();
  const nowYear = new Date().getFullYear();
  for (const m of text.slice(0, 4000).matchAll(/\b(20\d{2})\b/g)) {
    const y = Number(m[1]);
    if (y >= 2000 && y <= nowYear + 1) counts.set(y, (counts.get(y) || 0) + 1);
  }
  let best = null;
  for (const [y, n] of counts) if (!best || n > best[1]) best = [y, n];
  return best ? best[0] : null;
}

/**
 * @returns {{rows: Array, currency: string, unparsedCount: number}}
 */
export function parseStatement(text, baseCurrency, fallbackYear = new Date().getFullYear()) {
  const currency = detectCurrency(text, baseCurrency);
  const rows = [];
  let unparsed = 0;

  for (const rawLine of String(text).split('\n')) {
    const line = rawLine.trim().replace(/\s{2,}/g, '  ');
    if (line.length < 6) continue;
    if (isNoise(line)) continue;

    const date = findDate(line, fallbackYear);
    const amounts = findAmounts(line);
    if (!date || amounts.length === 0) continue;

    // The rightmost amount on a statement line is almost always the running
    // balance, so with two or more the transaction is the one before it.
    const txn = amounts.length >= 2 ? amounts[amounts.length - 2] : amounts[0];

    const description = describe(line, date, amounts);
    if (!description) { unparsed++; continue; }

    const isCredit = txn.isCredit === null ? looksLikeCredit(description) : txn.isCredit;

    let kind; let skipReason = null;
    if (isTransfer(description)) {
      kind = Kind.TRANSFER;
      skipReason = transferReason(description);
    } else if (isCredit) {
      kind = Kind.CREDIT;
      skipReason = 'Money in, not spending';
    } else {
      kind = Kind.SPEND;
    }

    rows.push({
      date: date.date,
      description,
      amountMinor: txn.minor,
      currency,
      kind,
      skipReason,
      rawLine: line,
    });
  }

  return { rows, currency, unparsedCount: unparsed };
}

export const spendsOf = (result) => result.rows.filter((r) => r.kind === Kind.SPEND);
export const skippedOf = (result) => result.rows.filter((r) => r.kind !== Kind.SPEND);
