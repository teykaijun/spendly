// Recognises money moving between your own accounts, which is not spending.
// Ported from TransferDetector.kt so the web app and the Android app exclude
// exactly the same things.
//
// The case that matters is an e-wallet reload. Topping up from your bank
// produces two records — the bank debit, and later whatever you buy with the
// balance. Counting both double-counts every ringgit.

const TRANSFER_KEYWORDS = [
  // E-wallet reloads
  'reload', 'reloaded', 'reloading', 're-load',
  'top up', 'top-up', 'topup', 'topped up', 'topping up',
  'add money', 'added money', 'add funds', 'added funds',
  'cash in', 'cash-in', 'cashin',
  'load wallet', 'wallet load', 'fund wallet', 'wallet funding',
  // Moving between your own accounts
  'transfer to own', 'own account', 'to your own',
  'between your accounts', 'self transfer', 'self-transfer',
  'balance transfer', 'internal transfer',
  // Card/wallet balance movements
  'moved to wallet', 'added to wallet', 'added to balance',
  'credited to your wallet', 'wallet credited',
];

function isWordChar(ch) {
  return ch !== undefined && /[a-z0-9]/i.test(ch);
}

/** Substring match that respects word boundaries, so "reload" misses "preloaded". */
function containsPhrase(haystack, phrase) {
  let from = 0;
  for (;;) {
    const i = haystack.indexOf(phrase, from);
    if (i < 0) return false;
    const before = haystack[i - 1];
    const after = haystack[i + phrase.length];
    if (!isWordChar(before) && !isWordChar(after)) return true;
    from = i + 1;
  }
}

/** True when the text describes a reload or a transfer between your own accounts. */
export function isTransfer(text) {
  if (!text) return false;
  const haystack = String(text).toLowerCase();
  return TRANSFER_KEYWORDS.some((k) => containsPhrase(haystack, k));
}

/** A short reason for the UI, so a skipped row explains itself. */
export function transferReason(text) {
  if (!text) return null;
  const haystack = String(text).toLowerCase();
  const hit = TRANSFER_KEYWORDS.find((k) => containsPhrase(haystack, k));
  return hit ? `Looks like a reload or transfer (“${hit}”), not spending` : null;
}
