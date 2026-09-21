# Changelog

Version numbers follow the tag on the GitHub release (`v1.0` → `1.0`). The
updater derives an Android `versionCode` from the tag, two digits per part, so
every component must stay under 100.

## v1.1 — notification spends now reach your records

### Fixed

- **Spends detected from notifications could look like they were never
  saved.** Two separate bugs, both silent:
  - The permission to show the *tap to confirm* alert was never requested on
    Android 13 and later. The alert setting defaulted to on, and the request
    only happened when that switch was flipped — which it never was. Detected
    spends queued in the Inbox with no prompt at all. Spendly now asks when you
    come back from granting notification access, and the Inbox says plainly
    when alerts are still blocked.
  - Ordinary bank alerts with a balance line — *"RM25.50 debited… Available
    balance: RM1,234.00"* — scored 50, just under the default sensitivity of
    55, and were dropped before reaching the Inbox. The running balance is no
    longer counted as a competing amount, and the expanded and collapsed text
    of a notification are no longer read twice.
- SMS bank alerts that arrive as a chat-style notification are now read from
  the message itself, not the "2 new messages" summary.
- Group-summary notifications are skipped, so a bundle and its contents are not
  counted twice.

### Added

- **Edit any entry.** Tap it in the calendar or on the Add screen to change the
  amount, currency, category, date, where, or note, or to delete it.
  Recategorising a spend that came from a notification also teaches Spendly
  that merchant, the same as correcting it in the Inbox.
- **What Spendly checked recently**, at the bottom of the Inbox: each
  notification that mentioned money, and what happened to it and why. It keeps
  the app name and amount only — never the message.

## v1.0 — first release

The first build published as a release, so it is the baseline the in-app updater
compares against. There is nothing to update *from* yet; install this one by
hand and later versions arrive through Settings → Updates.

### Recording a spend

- Two-tap entry: an ATM-style keypad with no decimal key (digits fill from the
  right, `1 2 5 0` → `12.50`), then tap a category, which saves it.
- Undo on every save.
- Optional "where" and note fields, collapsed so they never slow the common case.
- Today / Yesterday / any date, with the date sticking between entries.

### Reading notifications

- Parses bank, e-wallet and receipt-email notifications on-device.
- Detections queue in an Inbox for one-tap confirmation, or can be confirmed
  straight from the notification shade without opening the app.
- Correcting a guessed category writes a merchant rule, so the same merchant is
  filed correctly from then on.
- Chat, social and email apps start switched off; SMS apps start on, because
  banks text you.
- Sensitivity slider controls how certain a detection must be before it counts.

### Statements and reloads

- Import a bank or e-wallet **PDF statement**, including password-protected
  ones. Every row is shown for review before anything is written, and rows from
  an already-imported period start unticked so overlapping statements do not
  double up.
- **Reloads and self-transfers are never counted as spending** — in
  notifications, in statement imports, and therefore in every total. Moving
  money into a wallet is not a purchase, and counting it would double-count
  whatever you buy with that balance later. Paying a person by transfer still
  counts.

### Currencies

- 28 currencies, symbols and ISO codes, before or after the number, in both
  separator conventions. Verified by a 40-case sweep.
- Per-entry currency with a chip that highlights when it is not your default.
- The calendar switches which currency it is showing; nothing is ever converted.

### Seeing where it went

- Calendar heatmap scaled to the month's busiest day, tap any day for detail.
- Month total, per-active-day average, and a category breakdown.
- Home-screen widget with this month's total and a shortcut to the keypad.
- Date-range filter with presets and custom dates, showing totals and matching
  entries before you export.
- CSV export scoped to whatever range you picked.

### Privacy

- No code path sends spending data anywhere.
- `INTERNET` exists only for the updater, and only fires when you press the
  button — "check on open" ships off.
- The database is excluded from Google cloud backup; phone-to-phone transfer
  still works.
