// Money handling, ported from the Android app's Money.kt so the two agree on
// what "RM25.50" means. Amounts are integers in minor units (cents) — never
// floats — because 0.1 + 0.2 is not 0.3 and a spending tracker cannot drift.

const ZERO_DECIMAL = new Set(['JPY', 'KRW', 'VND', 'IDR', 'CLP', 'ISK']);

const SYMBOLS = {
  USD: '$', EUR: '€', GBP: '£', JPY: '¥', CNY: '¥',
  MYR: 'RM', SGD: 'S$', AUD: 'A$', CAD: 'C$', NZD: 'NZ$',
  HKD: 'HK$', TWD: 'NT$', BRL: 'R$', IDR: 'Rp', PHP: '₱',
  INR: '₹', THB: '฿', VND: '₫', KRW: '₩', TRY: '₺',
  ILS: '₪', NGN: '₦', RUB: '₽', PLN: 'zł', ZAR: 'R',
  CHF: 'CHF', SEK: 'kr', NOK: 'kr', DKK: 'kr',
  AED: 'AED', SAR: 'SAR', PKR: '₨', BDT: '৳', LKR: 'Rs',
};

export const COMMON_CURRENCIES = [
  'MYR', 'SGD', 'USD', 'EUR', 'GBP', 'AUD', 'CAD', 'NZD', 'HKD', 'TWD',
  'JPY', 'CNY', 'KRW', 'INR', 'IDR', 'THB', 'PHP', 'VND', 'CHF', 'SEK',
  'NOK', 'DKK', 'PLN', 'BRL', 'ZAR', 'TRY', 'AED', 'SAR', 'NGN', 'RUB',
];

export function symbol(currency) {
  return SYMBOLS[String(currency).toUpperCase()] ?? String(currency).toUpperCase();
}

export function decimals(currency) {
  return ZERO_DECIMAL.has(String(currency).toUpperCase()) ? 0 : 2;
}

/** Short symbols sit flush against the number; three-letter codes need a space. */
function needsSpace(sym) {
  return sym.length > 2;
}

/** "RM25.50" — the everyday display form. */
export function format(amountMinor, currency, withSymbol = true) {
  const body = formatPlain(amountMinor, currency);
  if (!withSymbol) return body;
  const sym = symbol(currency);
  return needsSpace(sym) ? `${sym} ${body}` : `${sym}${body}`;
}

/** The number alone, grouped, with the right number of decimals. */
export function formatPlain(amountMinor, currency) {
  const dp = decimals(currency);
  const units = Number(amountMinor) / 100;
  return units.toLocaleString('en-US', {
    minimumFractionDigits: dp,
    maximumFractionDigits: dp,
  });
}

/** Compact form for the heatmap cells: 1.2k, 45k, 1.1M. */
export function formatCompact(amountMinor, currency) {
  const units = Number(amountMinor) / 100;
  const sym = symbol(currency);
  let body;
  if (units >= 1_000_000) body = `${(units / 1_000_000).toFixed(1)}M`;
  else if (units >= 10_000) body = `${Math.round(units / 1000)}k`;
  else if (units >= 1000) body = `${(units / 1000).toFixed(1)}k`;
  else if (units >= 100) body = String(Math.round(units));
  // Drop cents only when they are actually zero — trimming trailing zeros off
  // "20.00" as a string would famously produce "2".
  else if (units === Math.floor(units)) body = String(units);
  else body = units.toFixed(2).replace(/0$/, '');
  return needsSpace(sym) ? `${sym} ${body}` : `${sym}${body}`;
}

/**
 * Parse a number the way it appears in the wild, where the thousands and
 * decimal separators differ by locale and by bank.
 *
 *  - Both `.` and `,` present -> whichever comes last is the decimal point.
 *  - One separator with 1-2 digits after it -> decimal point.
 *  - One separator with exactly 3 digits after it -> thousands.
 *
 * Returns minor units (always x100), or null when there is no usable number.
 */
export function parseToMinor(raw) {
  if (raw === null || raw === undefined) return null;
  const cleaned = String(raw).trim().replace(/ /g, ' ').replace(/ /g, '');
  if (!cleaned || !/\d/.test(cleaned)) return null;

  const lastDot = cleaned.lastIndexOf('.');
  const lastComma = cleaned.lastIndexOf(',');

  let normalized;
  if (lastDot >= 0 && lastComma >= 0) {
    normalized = lastDot > lastComma
      ? cleaned.replace(/,/g, '')
      : cleaned.replace(/\./g, '').replace(',', '.');
  } else if (lastDot >= 0) {
    normalized = normalizeSingle(cleaned, '.', lastDot);
  } else if (lastComma >= 0) {
    normalized = normalizeSingle(cleaned, ',', lastComma);
  } else {
    normalized = cleaned;
  }

  const digitsOnly = normalized.replace(/[^0-9.]/g, '');
  if (!digitsOnly || digitsOnly === '.') return null;

  const value = Number(digitsOnly);
  if (!Number.isFinite(value)) return null;
  // Round half-up on the scaled value to avoid float representation error.
  return Math.round(value * 100);
}

function normalizeSingle(s, sep, lastIndex) {
  const after = s.length - lastIndex - 1;
  const occurrences = s.split(sep).length - 1;
  const escaped = sep === '.' ? '\\.' : ',';
  if (occurrences > 1) return s.replace(new RegExp(escaped, 'g'), '');
  if (after === 3) return s.replace(new RegExp(escaped, 'g'), '');
  if (after >= 1 && after <= 2) return s.replace(sep, '.');
  return s.replace(new RegExp(escaped, 'g'), '');
}
