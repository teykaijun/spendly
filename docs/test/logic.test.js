// Run with:  node --test docs/test/
//
// These are the Kotlin tests ported across. Keeping the same cases on both
// platforms is the point: if a statement reads correctly on Android it must
// read the same on the web app, and a fix to one is a failing test in the other.

import test from 'node:test';
import assert from 'node:assert/strict';

import { format, formatCompact, formatPlain, parseToMinor, decimals, symbol } from '../js/money.js';
import { isTransfer } from '../js/transfer.js';
import { parseStatement, detectYear, Kind, spendsOf, skippedOf } from '../js/statement.js';

// ---------------------------------------------------------------- money ----

test('money parsing handles messy separators', () => {
  assert.equal(parseToMinor('25.50'), 2550);
  assert.equal(parseToMinor('25,50'), 2550);
  assert.equal(parseToMinor('1,234.56'), 123456);
  assert.equal(parseToMinor('1.234,56'), 123456);
  assert.equal(parseToMinor('1,000'), 100000);
  assert.equal(parseToMinor('5'), 500);
  assert.equal(parseToMinor('abc'), null);
  assert.equal(parseToMinor(''), null);
});

test('money parsing does not drift on float representation', () => {
  // 1.15 * 100 is 114.99999999999999 in IEEE754; rounding must save it.
  assert.equal(parseToMinor('1.15'), 115);
  assert.equal(parseToMinor('8.29'), 829);
  // Exactly three digits after a lone separator means thousands, so this is
  // one thousand and five — the same reading the Android app gives it.
  assert.equal(parseToMinor('1.005'), 100500);
  assert.equal(parseToMinor('1,005'), 100500);
});

test('money formatting puts the symbol in the right place', () => {
  assert.equal(format(2550, 'MYR'), 'RM25.50');
  assert.equal(format(123456, 'USD'), '$1,234.56');
  assert.equal(format(4500, 'CHF'), 'CHF 45.00');
  assert.equal(format(120000, 'JPY'), '¥1,200'); // zero-decimal
});

test('compact formatting keeps whole amounts whole', () => {
  // Regression: trimming trailing zeros off "20.00" used to yield "2".
  assert.equal(formatCompact(2000, 'MYR'), 'RM20');
  assert.equal(formatCompact(5000, 'MYR'), 'RM50');
  assert.equal(formatCompact(2050, 'MYR'), 'RM20.5');
  assert.equal(formatCompact(9999, 'MYR'), 'RM99.99');
  assert.equal(formatCompact(15000, 'MYR'), 'RM150');
  assert.equal(formatCompact(120000, 'MYR'), 'RM1.2k');
  assert.equal(formatCompact(4500000, 'MYR'), 'RM45k');
});

test('zero decimal currencies drop the cents', () => {
  assert.equal(decimals('JPY'), 0);
  assert.equal(decimals('MYR'), 2);
  assert.equal(formatPlain(125000, 'JPY'), '1,250');
  assert.equal(symbol('MYR'), 'RM');
});

// ------------------------------------------------------------- transfers ----

test('every reload phrasing is caught', () => {
  for (const p of [
    'RELOAD TNG EWALLET', 'Reloaded Touch n Go', 'TOP UP GRABPAY',
    'Top-up Boost Wallet', 'TOPUP SHOPEEPAY', 'Add Money to Wallet',
    'Cash In via Maybank', 'Transfer to own account', 'Internal transfer',
    'Balance transfer',
  ]) {
    assert.equal(isTransfer(p), true, `should be a transfer: ${p}`);
  }
});

test('paying a person is still spending, not a transfer', () => {
  assert.equal(isTransfer('DUITNOW TRANSFER TO ALI BIN ABU'), false);
  assert.equal(isTransfer('Payment to landlord'), false);
});

test('reload words inside other words do not fire', () => {
  assert.equal(isTransfer('PRELOADED CARD PURCHASE'), false);
  assert.equal(isTransfer('Cashing a cheque'), false);
});

// ------------------------------------------------------------ statements ----

const parse = (text, base = 'MYR', year = 2026) =>
  parseStatement(text.split('\n').map((l) => l.trim()).join('\n'), base, year);

test('reads a bank statement with a running balance column', () => {
  const r = parse(`
    01/02/2026  STARBUCKS KLCC            25.50      1,234.00
    03/02/2026  PETRONAS BANGSAR          80.00      1,154.00
    05/02/2026  MYDIN SUBANG             120.75      1,033.25
  `);
  const spends = spendsOf(r);
  assert.equal(spends.length, 3);
  // The balance column must not be mistaken for the transaction.
  assert.equal(spends[0].amountMinor, 2550);
  assert.equal(spends[1].amountMinor, 8000);
  assert.equal(spends[2].amountMinor, 12075);
  assert.equal(spends[0].date, '2026-02-01');
  assert.match(spends[0].description, /STARBUCKS/i);
});

test('a single amount on the line is the transaction', () => {
  const r = parse('01/02/2026  STARBUCKS KLCC   25.50');
  assert.equal(spendsOf(r).length, 1);
  assert.equal(spendsOf(r)[0].amountMinor, 2550);
});

test('reload lines are excluded from spending', () => {
  const r = parse(`
    01/02/2026  STARBUCKS KLCC            25.50      1,234.00
    02/02/2026  RELOAD TNG EWALLET        50.00      1,184.00
    03/02/2026  TOP UP GRABPAY           100.00      1,084.00
  `);
  assert.equal(spendsOf(r).length, 1);
  assert.equal(spendsOf(r)[0].amountMinor, 2550);
  const skipped = skippedOf(r);
  assert.equal(skipped.length, 2);
  assert.ok(skipped.every((s) => s.kind === Kind.TRANSFER));
  assert.ok(skipped.every((s) => s.skipReason));
});

test('credits are excluded from spending', () => {
  const r = parse(`
    01/02/2026  STARBUCKS KLCC            25.50      1,234.00
    25/02/2026  SALARY CREDIT          3,000.00      4,234.00
    26/02/2026  REFUND SHOPEE             45.00      4,279.00
  `);
  assert.equal(spendsOf(r).length, 1);
  assert.equal(skippedOf(r).filter((s) => s.kind === Kind.CREDIT).length, 2);
});

test('explicit CR and DR markers win over keywords', () => {
  const r = parse(`
    01/02/2026  SOMETHING AMBIGUOUS     100.00 CR   1,000.00
    02/02/2026  SOMETHING AMBIGUOUS      40.00 DR     960.00
  `);
  assert.equal(spendsOf(r).length, 1);
  assert.equal(spendsOf(r)[0].amountMinor, 4000);
});

test('reads a signed e-wallet statement', () => {
  const r = parse(`
    01 Feb 2026   Payment to Starbucks      -25.50
    02 Feb 2026   Reload from Maybank       +50.00
    03 Feb 2026   Payment to Grab           -18.00
  `);
  assert.equal(spendsOf(r).length, 2);
  assert.equal(spendsOf(r)[0].amountMinor, 2550);
  assert.equal(spendsOf(r)[1].amountMinor, 1800);
  assert.equal(skippedOf(r).length, 1);
  assert.equal(skippedOf(r)[0].kind, Kind.TRANSFER);
});

test('reads the common date formats', () => {
  assert.equal(spendsOf(parse('01/02/2026  SHOP  10.00'))[0].date, '2026-02-01');
  assert.equal(spendsOf(parse('2026-02-01  SHOP  10.00'))[0].date, '2026-02-01');
  assert.equal(spendsOf(parse('01-02-2026  SHOP  10.00'))[0].date, '2026-02-01');
  assert.equal(spendsOf(parse('01 Feb 2026  SHOP  10.00'))[0].date, '2026-02-01');
});

test('year-less dates use the statement year', () => {
  assert.equal(spendsOf(parse('01 FEB  SHOP  10.00', 'MYR', 2025))[0].date, '2025-02-01');
});

test('detects the statement year from the document', () => {
  assert.equal(detectYear('Statement period 01/01/2025 to 31/01/2025'), 2025);
});

test('an impossible date is rejected rather than rolled over', () => {
  // 31 February must not silently become 3 March.
  assert.equal(parse('31/02/2026  SHOP  10.00').rows.length, 0);
});

test('header footer and balance lines are ignored', () => {
  const r = parse(`
    MAYBANK BERHAD STATEMENT OF ACCOUNT
    Account Number 1234567890
    OPENING BALANCE                        1,259.50
    01/02/2026  STARBUCKS KLCC    25.50    1,234.00
    CLOSING BALANCE                        1,234.00
    Page 1 of 3
  `);
  assert.equal(spendsOf(r).length, 1);
  assert.match(spendsOf(r)[0].description, /STARBUCKS/i);
});

test('bare integers are not treated as amounts', () => {
  const r = parse('01/02/2026  PURCHASE CARD ENDING 1234  25.50  1,234.00');
  assert.equal(spendsOf(r).length, 1);
  assert.equal(spendsOf(r)[0].amountMinor, 2550);
});

test('lines without a date are skipped', () => {
  assert.equal(parse('STARBUCKS KLCC  25.50').rows.length, 0);
});

test('description strips the date and the amounts', () => {
  const d = spendsOf(parse('01/02/2026  STARBUCKS KLCC  25.50  1,234.00'))[0].description;
  assert.equal(d, 'STARBUCKS KLCC');
});

test('picks up the statement currency from the document', () => {
  const r = parse('Statement in RM 0.00\n01/02/2026  STARBUCKS  25.50  1,234.00', 'SGD');
  assert.equal(r.currency, 'MYR');
});

test('falls back to the default currency when the document says nothing', () => {
  assert.equal(parse('01/02/2026  STARBUCKS  25.50  1,234.00', 'SGD').currency, 'SGD');
});

test('an empty statement parses to nothing rather than throwing', () => {
  assert.equal(parse('').rows.length, 0);
});
