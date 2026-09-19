# Spendly

An Android spending tracker built around one idea: **if logging a spend takes
more than two taps, you won't do it.**

Three things it does:

1. **Two-tap manual entry** — type the amount on a big keypad, tap a category, done.
   Pick a different currency per entry when you're abroad.
2. **Reads your notifications** — spots spends in bank and e-wallet alerts and
   fills the entry in for you. You confirm with one tap (or straight from the
   notification shade, without opening the app).
3. **Calendar heatmap** — see at a glance which days were expensive, tap any day
   to see exactly what you spent.

Everything stays on the phone. Nothing is uploaded anywhere.

---

## Getting it onto your phone

### Option A — just install the APK (fastest)

A working debug APK is already built at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Copy it to your phone (USB, Google Drive, Telegram to yourself — anything), tap
it, and allow "install from unknown sources" when prompted. That's it.

> Use the **debug** APK for your first install. There's a release build too
> (`app/build/outputs/apk/release/app-release.apk`, ~1.6 MB vs ~12 MB) but it's
> passed through code shrinking, which hasn't been verified on a real device.

### Option B — build it yourself

You'll want this if you plan to change anything.

1. Install [Android Studio](https://developer.android.com/studio) (free). Pick
   the standard setup; it brings its own JDK and Android SDK.
2. Open Android Studio → **Open** → select this folder (`SpendManagementApp`).
3. Wait for the Gradle sync to finish.
4. Plug in your phone with USB debugging on, or start an emulator, and press
   **Run** (▶).

Android Studio will write its own `local.properties` pointing at your SDK.

<details>
<summary>Building from the command line instead</summary>

You need a JDK 17+ and an Android SDK with API 37. On the machine this was
built on:

```bash
JAVA_HOME="/c/Program Files/Android/openjdk/jdk-21.0.8" ./gradlew assembleDebug
```

Other useful targets:

```bash
./gradlew testDebugUnitTest   # run the parser tests
./gradlew lintDebug           # static analysis
./gradlew assembleRelease     # minified build
```

`local.properties` points Gradle at your SDK and is git-ignored — recreate it as
`sdk.dir=C\:/path/to/Android/Sdk` if you clone this fresh.

</details>

---

## First run

1. Open the app. Go to the **Inbox** tab.
2. Tap **Open notification access** and switch Spendly on in the system list.
   Android will warn that the app can read all notifications — it can, and it
   has to, because that's where your bank alerts are.
3. Go to **Settings** and set your **default currency** if it guessed wrong.
   (You can still pick a different currency per entry.)
4. Optionally turn on **Alert me when a spend is found** so you can confirm
   spends from the notification shade.

That's the whole setup.

---

## Using it

### Adding a spend manually

The **Add** tab opens on a big keypad. Digits fill from the right, like an ATM —
tap `1` `2` `5` `0` and you get `12.50`. **There is no decimal key**, because
mistyping the decimal point is the single most common entry error.

Then tap a category. That saves it. Two interactions, no keyboard, no dialogs.

- Wrong? A snackbar with **Undo** appears after every save.
- Need to note where it was? Tap **Add where / note** — it stays collapsed
  otherwise so it never slows down the common case.
- Backdating? The `Today` / `Yesterday` / `Pick date` chips are at the top. The
  date sticks between entries, so logging a whole day at once is quick.
- Prefer pressing Save yourself? Turn off **One-tap save** in Settings.

### Spending in several currencies

Every entry stores its own currency. Above the amount there's a currency chip —
tap it to switch. Recently used currencies are listed first, so hopping between
the two or three you actually use doesn't mean scrolling a list of thirty.

The chip **turns solid and bold whenever it isn't your default currency**, and
the choice deliberately sticks across saves (abroad you log several in a row) —
so the non-default state is impossible to miss when you get home.

On the calendar, when a month contains more than one currency you get a
**Showing** row of chips above the grid. The heatmap, month total and category
breakdown all follow whichever you pick; the others are listed underneath.

**Nothing is ever converted between currencies.** The app holds no exchange
rates and doesn't fetch any, so it will never quietly turn ¥12,000 into a ringgit
figure using a rate from some arbitrary date. You switch the view instead.

### Automatic capture

When a notification arrives, Spendly parses it on-device and, if it looks like a
spend, drops it in the **Inbox** with a confidence score. You either:

- tap **Confirm** (accepts the guessed category), or
- tap the category pill to pick a different one — which also **teaches it**, so
  that merchant is filed correctly forever after, or
- tap **Not a spend** to bin it.

There's also **Confirm all** when you have a backlog.

If notification alerts are on, you get Confirm / Not-a-spend buttons directly in
the shade, so a spend can be recorded without ever opening the app.

### Subscription and invoice emails

Turn **Gmail on** in Settings → "Apps Spendly listens to" (it starts off, like
all chat and email apps). Spendly then reads the notification Gmail already
posts — sender in the title, subject and snippet in the body — which is enough
for most receipts:

> **Netflix** · Your receipt from Netflix — RM54.90

This needs **no access to your mail account**, no Google sign-in, and no network
permission. See [Connecting to Gmail properly](#connecting-to-gmail-properly)
for what the alternative would cost.

The parser knows the receipt vocabulary — *receipt, invoice, billed, subscription
renewed, auto-renewed, order confirmation, we received your payment, you were
charged* — and rejects the marketing that surrounds it: *unsubscribe, view in
browser, price drop, starting at, as low as, your cart, back in stock*.

Its blind spot is emails that only put the amount in the body, since Gmail
truncates the snippet. Those are missed, not misread.

### The calendar

Each day is tinted by how much you spent, scaled against that month's busiest
day. Tap any day to see its entries. Below the grid you get the month total, the
average per active day, and a breakdown by category.

---

## How the notification reading actually works

The parser is in [`app/src/main/java/com/spendly/parser/`](app/src/main/java/com/spendly/parser/)
and is deliberately conservative. A notification becomes a candidate spend only
if **all three** hold:

1. It contains a currency amount (`RM25.50`, `S$18.90`, `€1.234,56`, `₹1,250`,
   `88.00 MYR` — symbols and ISO codes, before or after the number, in either
   separator convention).
2. It contains a spend verb — *paid, spent, debited, charged, purchase,
   deducted, DuitNow, …*
3. It contains none of the disqualifiers — *refund, credited, received, OTP,
   verification code, cashback, % off, failed, declined, due on, …*

On top of that:

- **Multiple amounts** → it picks the one nearest the spend verb, so
  `"RM25.50 spent at MYDIN. Available balance: RM1,234.00"` records 25.50.
- **Ambiguous `$`** resolves to your base currency, so `$` means SGD if you're in
  Singapore. `US$` is never read as `S$`.
- **Duplicates** are suppressed for 6 hours on (app + amount + currency +
  merchant), because apps re-post notifications constantly.
- **Word boundaries** are enforced — `PLATFORM 25` isn't `RM 25`, `10 USDT`
  isn't `10 USD`.
- **Chat, social and email apps start switched off** (in Settings, ready to be
  turned on) because people write "I paid RM50 at the mall" to each other. SMS
  apps stay **on**, since that's how many banks send alerts.

### When it misses something

Add the notification text as a test case rather than guessing:

```kotlin
// app/src/test/java/com/spendly/parser/NotificationParserTest.kt
@Test
fun `handles my bank`() {
    val r = parse("MyBank", "Txn of RM42.00 at SOME SHOP")
    assertEquals(4200L, r!!.amountMinor)
}
```

```bash
./gradlew testDebugUnitTest
```

Then add the missing keyword to `SPEND_KEYWORDS` or `REJECT_KEYWORDS` in
`NotificationParser.kt`. There are 30 tests covering the cases above, so you'll
know immediately if a change breaks something else.

You can also move the **Sensitivity** slider in Settings — it's the minimum
confidence a detection needs before Spendly records it at all.

---

## Privacy

Short version: **the app has no internet permission**, so it cannot send your
data anywhere even if there were a bug that tried to.

You can check that claim yourself rather than taking my word for it:

```bash
# The merged manifest is the final word on what the app can do.
grep uses-permission app/build/intermediates/merged_manifest/debug/*/AndroidManifest.xml
```

The only permission is `POST_NOTIFICATIONS` (to show the tap-to-confirm alerts).
There is no `INTERNET`, no `ACCESS_NETWORK_STATE`, and no analytics, crash
reporting or telemetry SDK anywhere in the dependency list.

What happens to your notification and email text:

| | |
|---|---|
| **Read** | Only while Android is delivering the notification, in this app's own process. |
| **Parsed** | In memory, by plain Kotlin string matching in `parser/`. No model, no service, no network. |
| **Stored on a confirmed entry** | Amount, currency, merchant, category, date, source app name. **Not** the notification text. |
| **Stored while pending** | The raw title and text, so you can see what a detection came from. Deleted the moment you confirm or dismiss, and auto-pruned after 30 days. |
| **Logged** | Package names and error types only — never notification content. |

Two more things worth knowing:

- **Your history is excluded from Google cloud backup.** Android's backup agent
  would otherwise upload the database to your Google Drive, which is data
  leaving the phone through a system path the app doesn't control. Phone-to-phone
  transfer is still enabled, because that's a local transfer.
- **CSV export is the only way data leaves**, and only when you tap it and pick
  where it goes. It's shared through a `FileProvider` scoped to one cache folder.

Notification access is a genuinely broad permission — Spendly *can* see every
notification on the phone, and Android warns you about exactly that when you
grant it. What it does with that is bounded by the above: no network to send it
to, and chat/social/email apps switched off by default. **Pause capture** in
Settings stops entries being created without revoking the permission.

### Connecting to Gmail properly

You asked whether connecting Gmail directly would be better. It would read
receipts more reliably — full email bodies instead of a truncated notification
snippet — but it costs the property this whole section describes:

| | Notification reading (today) | Gmail API |
|---|---|---|
| Internet permission | not present | **required** |
| Google sign-in / OAuth | none | required |
| Scope requested | none | `gmail.readonly` — *all* your mail, not just receipts |
| Setup | flip a switch | Google Cloud project, OAuth consent screen, add yourself as a test user |
| Sees | sender, subject, snippet | full message bodies |
| "No network" guarantee | structural | gone |

`gmail.readonly` is a **restricted scope**. For personal use you can keep the
OAuth app in Testing mode and add your own address as a test user, which works
without Google's security assessment — but the app would still hold a token that
can read your entire mailbox, and would need network access to use it.

**My recommendation: try the notification path first.** It's already built, it
costs nothing, and subscription receipts put the amount in the subject line more
often than not. If after a few weeks you find it's missing the ones you care
about, that's real evidence the API is worth the trade — and I can build it
then, ideally behind a switch so the network code is only ever live if you turn
it on.

## Project layout

```
app/src/main/java/com/spendly/
├── data/           Room entities, DAOs, repository, money formatting, prefs
├── parser/         Notification → spend. Pure Kotlin, no Android deps, tested
├── notify/         NotificationListenerService + the confirm-from-shade actions
└── ui/
    ├── quickadd/   The two-tap entry screen
    ├── calendar/   Heatmap grid + day drill-down
    ├── inbox/      The confirm queue
    ├── settings/   Currency, capture controls, per-app switches, CSV export
    └── theme/      Material 3 theme and the heatmap colour ramp
```

Key decisions worth knowing about:

- **Money is stored as `Long` minor units**, never floating point. Currencies
  with no minor unit (JPY, KRW, VND) are still stored ×100 and formatted without
  decimals.
- **Month totals are computed in Kotlin, not SQL.** `SUM()` across mixed
  currencies is simply wrong, so the calendar sums only the currency you are
  currently viewing and reports the others separately. The app holds no exchange
  rates and doesn't invent any.
- **The heatmap ramp is a fixed green scale**, not the Material You accent — the
  whole point of the grid is comparing one cell against another, and that
  shouldn't depend on the wallpaper. Both the light and dark ramps were checked
  with a palette validator for monotone lightness, visible gaps between steps,
  single hue, and contrast against this app's surfaces.
- **The lightest heat step is not near-white.** A barely-tinted cell reads as an
  empty day, so "spent a little" would look like "spent nothing". Days with no
  spending get no fill at all instead.

## Toolchain

Pinned to what was installed on the build machine, to keep a clean build cheap:

| | |
|---|---|
| Gradle | 8.14.1 wrapper → 9.7.1 |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.4.20 (AGP built-in — there is no separate Kotlin plugin) |
| Compose BOM | 2026.09.00 |
| Room | 2.8.5 (KSP 2.3.12) |
| compileSdk / targetSdk | 37 |
| minSdk | 26 (Android 8.0) |

## Known limits

- **Android only.** iOS gives apps no way to read other apps' notifications, so
  the automatic half of this app cannot exist there.
- **No currency conversion.** Deliberate — see above. You switch which currency
  the calendar is showing rather than getting a blended total from an invented
  rate.
- **The UI has not been run on a device.** It compiles, passes lint with zero
  findings, and the parser has 30 passing unit tests — but there was no emulator
  or phone available on the build machine, so layout and interaction haven't
  been exercised. Expect to nudge some spacing.
- Editing an existing entry isn't implemented yet — you delete and re-add.
- Categories are fixed to the built-in ten; adding your own isn't wired up yet.
