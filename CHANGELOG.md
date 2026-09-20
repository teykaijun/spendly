# Changelog

Version numbers follow the tag on the GitHub release (`v1.0` → `1.0`). The
updater derives an Android `versionCode` from the tag, two digits per part, so
every component must stay under 100.

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
