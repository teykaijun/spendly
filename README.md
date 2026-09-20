# Spendly

[![Latest release](https://img.shields.io/github/v/release/teykaijun/spendly?label=download&color=10A37F)](https://github.com/teykaijun/spendly/releases/latest)

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
4. **Home-screen widget** — this month's total without opening anything, plus a
   shortcut straight to the keypad.
5. **Range filter and CSV export** — any two dates, or one tap for this month.
6. **PDF statement import** — read a month of spending out of a bank or e-wallet
   statement, with every row shown for review first.

Your spending data never leaves the phone. The only network access in the app
is the optional updater, and it only runs when you press the button — see
[Privacy](#privacy).

---

## Getting it onto your phone

### Option A — download the release (fastest)

**[Download the latest APK](https://github.com/teykaijun/spendly/releases/latest)**
on the phone itself, tap it, and allow "install from unknown sources" when
prompted. That's it.

That build updates itself from then on: Settings → Updates, with the source set
to `teykaijun/spendly`.

> Install the **release** APK, not a local debug build. Debug builds carry a
> `.debug` applicationId, which Android treats as a separate app, so they can
> never update themselves — and they are signed with a per-machine throwaway key
> rather than the release key.

Every release is signed with the same key. If you ever want to check a download
is genuinely the same build:

```
SHA-256: cb:9e:2c:08:4f:76:46:97:15:86:52:e2:01:9d:0f:16:
         a6:1c:11:de:45:bc:19:0c:eb:df:e4:57:d3:e4:85:ac
```

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

### Importing a PDF statement

Settings → **Import a PDF statement**. Pick a bank or e-wallet PDF and Spendly
reads it on the phone — the file is never uploaded.

Password-protected statements are supported, which most bank PDFs are. The
password is used to open the file and is not stored.

**Every row is shown for review before anything is written.** Statement layouts
are not standardised in any useful sense — every bank invents its own column
order, date format and debit marker — so the parse is frankly heuristic and the
review step is not optional. Untick anything wrong, change a category inline,
then import.

Rows already imported from an overlapping statement are detected and start
**unticked**, so re-importing an overlapping period does not double everything.

What it handles:

| | |
|---|---|
| **Layouts** | `date · description · amount · balance` (the rightmost amount is read as the running balance, not the transaction), and signed e-wallet rows like `-25.50` / `+50.00` |
| **Dates** | `01/02/2026`, `2026-02-01`, `01-02-2026`, `01 Feb 2026`, and year-less `01 FEB` resolved against the statement's own year |
| **Direction** | explicit `CR` / `DR` markers and `+` / `-` signs win; otherwise inferred from words like *salary*, *refund*, *purchase*, *fee* |
| **Noise** | opening/closing balance, page footers, account numbers and summary lines are skipped |

Its limits, plainly: a **scanned** statement has no text layer and cannot be
read — Spendly reads text, not images, and says so rather than importing
nothing silently. Layouts with separate debit and credit columns are the most
likely to need a correction in review.

### Reloads are not spending

Topping up an e-wallet moves money between your own accounts. It is not a
purchase, and counting it double-counts every ringgit — once when it enters the
wallet, and again when you actually buy something with it.

So **reloads and self-transfers are excluded everywhere**: notifications,
statement imports, and therefore every total, the heatmap and the widget. The
same [`TransferDetector`](app/src/main/java/com/spendly/parser/TransferDetector.kt)
decides in both places, so the two can't drift apart.

Excluded: *reload, top up / top-up / topup, add money, add funds, cash in,
load wallet, transfer to own account, internal transfer, balance transfer.*

**Still counted**, because they are genuinely spending: paying a person by bank
transfer, DuitNow to someone, and any merchant whose name happens to contain one
of those words — matching is word-boundary aware, so `PRELOADED CARD SHOP` is
not a reload.

In a statement import, excluded rows are listed under *"Show N left out"* with
the reason, so you can see what was dropped instead of wondering why the total
looks short.

### The calendar

Each day is tinted by how much you spent, scaled against that month's busiest
day. Tap any day to see its entries. Below the grid you get the month total, the
average per active day, and a breakdown by category.

### The home-screen widget

Long-press the home screen → **Widgets** → Spendly → *Spending this month*.

It shows the month's total in your default currency, the entry count and the
average per day *elapsed* (on the 3rd it divides by 3, not by 30 — a month-long
denominator early in the month tells you nothing). Spending in other currencies
is noted on a second line rather than folded into the headline.

Tapping it opens the app; tapping **+ Add spend** goes straight to the keypad.

It redraws whenever an entry is added, edited, deleted or confirmed, and on the
half hour as a fallback so the month rolls over on its own.

### Filtering and exporting a date range

Settings → **Export spending**, or the share icon on the calendar.

Pick a preset — *This month*, *Last month*, *Last 30 days*, *This year*,
*Everything* — or **Custom** for any two dates. The sheet then shows the totals
and every matching entry **before** you export, so it doubles as "what did I
spend between these dates" and the file is never a surprise.

**Export CSV** writes one row per entry with the date, amount, currency,
category, merchant, note and whether it was manual or automatic. Totals are
listed per currency and never summed across them.

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

### Currencies, verified

`CurrencyCoverageTest` sweeps 40 realistic notifications and checks both the
amount *and* the resolved ISO code for each. All 40 pass. Covered:

| | |
|---|---|
| **Symbols** | `RM` `S$` `US$` `A$` `C$` `NZ$` `HK$` `NT$` `R$` `Rp` `€` `£` `₹` `₱` `฿` `₫` `₩` `₺` `₪` `₦` `₽` `₨` `CHF` `AED` `SAR` |
| **ISO codes** | before *and* after the number — `MYR 88.00` and `88.00 MYR` both work |
| **Separators** | `1,234.56` and `1.234,56` both read as the same amount |
| **Zero-decimal** | JPY, KRW, VND, IDR display without cents |

Three cases deserve singling out:

- **`US$` is never read as `S$`.** The token list puts the longer prefixes first
  precisely so a US dollar amount cannot become Singapore dollars.
- **A bare `$` or `¥` resolves to your default currency** — `$` reads as SGD for
  a Singapore default, AUD for an Australian one, and USD when your default is
  neither. Because that is a guess, such detections score lower confidence than
  an explicit symbol does.
- **`kr` is deliberately not recognised.** It is Swedish, Norwegian and Danish at
  once, and guessing wrong is worse than missing it. `SEK 199` works fine.

A foreign card transaction keeps the currency it was charged in: `"Your card was
charged THB 1,299.00 at BANGKOK HOTEL"` records 1,299.00 THB, not a converted
figure. And when a notification quotes both — `"You paid $42.00 (RM198.00)"` —
it takes the one nearest the spend verb, so the transaction amount wins over the
parenthetical conversion.

If a currency you use is missing, add it to `SYMBOL_TOKENS` in
[`AmountDetector.kt`](app/src/main/java/com/spendly/parser/AmountDetector.kt)
and add a line to the sweep.

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
`NotificationParser.kt`. There are 40 parser tests covering the cases above, so you'll
know immediately if a change breaks something else.

You can also move the **Sensitivity** slider in Settings — it's the minimum
confidence a detection needs before Spendly records it at all.

---

## Updating the app

Settings → **Updates**. Set an update source once, then one button does the
whole thing: check, download, verify, install.

### What "automatically" can and cannot mean

The app downloads and verifies the new build by itself, then opens Android's
installer. **You still have to tap "Install" on the system dialog.** Installing
without that requires the privileged `INSTALL_PACKAGES` permission, which only
system and device-owner apps can hold. No sideloaded app can skip it — if one
claims to, it is either preinstalled or lying.

So: one tap instead of "find the APK, open a file manager, tap through a
browser warning", but not unattended.

### Setting up the source

Two shapes are accepted in the **Update source** box:

**A GitHub repository** — type `your-name/spendly` (or paste the repo URL). The
app reads the latest *published* release (drafts and pre-releases are skipped)
and looks for an `.apk` asset. The version comes from the tag: `v1.2.0` or
`1.2.0`, where each part must stay under 100 (`1.4.2` sorts after `1.4.1`, and
`1.10` correctly outranks `1.9`).

**A JSON file you host** — paste an `https://` URL serving:

```json
{
  "versionCode": 12,
  "versionName": "1.2.0",
  "apkUrl": "https://example.com/spendly-1.2.0.apk",
  "sizeBytes": 12459474,
  "notes": "What changed",
  "sha256": "optional lowercase hex digest of the apk"
}
```

`http://` is refused in both cases, not silently upgraded — the thing being
fetched gets installed.

### You need a stable signing key first

**This is the part that catches everyone.** Android refuses to install an update
signed with a different key than the installed app. The debug keystore is
generated per machine, so builds from two computers can never update each other.

Create one release keystore and keep it safe:

```bash
keytool -genkeypair -v -keystore spendly-release.jks -alias spendly -keyalg RSA -keysize 4096 -validity 10000
```

Then copy `keystore.properties.example` to `keystore.properties` (git-ignored)
and fill it in. Builds without that file still work — they fall back to debug
signing — they just can't be updated from elsewhere.

> Lose that keystore and you can never update an installed copy again, only
> uninstall and reinstall. Back it up somewhere other than this repo.

### Publishing a new version

```bash
./scripts/release.sh --dry-run   # build and verify, publish nothing
./scripts/release.sh             # tag, build, verify, publish
```

The script runs the tests and lint, builds the release APK, **refuses to
continue if it is signed with the Android debug key**, tags the commit, and
publishes a GitHub release whose notes come from the matching `CHANGELOG.md`
section — so what the app shows you in the update card is the same text the
repository documents.

For a new version, bump `versionCode` and `versionName` in
`app/build.gradle.kts`, add a `## vX.Y` section to `CHANGELOG.md`, commit, then
run the script.

> **Debug builds cannot update themselves.** The debug variant is
> `com.spendly.debug`, which Android treats as a different app from the release
> `com.spendly`. Install a release APK once by hand; that copy updates from then
> on. The app says so in Settings → Updates rather than failing confusingly.

### What is checked before anything installs

A downloaded APK is code that is about to run on your phone, so it is verified
before the installer ever sees it:

- **HTTPS throughout**, with redirects re-checked at every hop, and cleartext
  blocked by network security config.
- **SHA-256** matched, when the feed publishes one.
- **Package name** must be this app's.
- **Version** must be strictly newer than what is installed.
- **Signing certificate** must match the installed app's.

Anything that fails deletes the file and says why, instead of handing a
suspicious APK to the system installer.

---

## Privacy

**Your spending data never leaves the phone.** Nothing reads the database and
writes it to a network socket — there is no such code path, and no analytics,
crash-reporting or telemetry SDK anywhere in the dependency list.

The app does now hold `INTERNET`, because the in-app updater needs it. You can
see exactly what it has:

```bash
# The merged manifest is the final word on what the app can do.
grep uses-permission app/build/intermediates/merged_manifest/debug/*/AndroidManifest.xml
```

| Permission | What it is for |
|---|---|
| `POST_NOTIFICATIONS` | The tap-to-confirm alerts. |
| `INTERNET` | **Updater only.** Two HTTPS requests: read the release feed, download the APK. |
| `REQUEST_INSTALL_PACKAGES` | **Updater only.** Hand that APK to Android's installer. Does *not* allow silent installs. |

> **This is a real change from the first version, which had no network access at
> all.** That was a structural guarantee — the app *couldn't* leak data because
> it had no way to. Now the guarantee is behavioural: it doesn't, because no code
> does. That is weaker, and it is the price of the update button. If you would
> rather have the stronger version back, deleting the `update/` package and the
> two permissions restores it, and the rest of the app is untouched.

The updater makes **no request unless you press "Check for updates"**, unless you
turn on "Check when the app opens", which ships off.

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
- **CSV export is the only way your spending data leaves**, and only when you
  tap it and pick where it goes. It's shared through a `FileProvider` scoped to
  one cache folder.
- **The updater only ever sends a plain GET.** No identifiers, no device
  information beyond what any HTTP client sends, no request body.

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
├── update/         The in-app updater: feed, download, verification, install
├── widget/         Home-screen widget (RemoteViews, not Compose)
├── pdf/            Statement text extraction and line parsing
└── ui/
    ├── quickadd/   The two-tap entry screen
    ├── calendar/   Heatmap grid + day drill-down
    ├── inbox/      The confirm queue
    ├── export/     Date-range filter and CSV export
    ├── importing/  PDF statement import and review
    ├── settings/   Currency, capture controls, per-app switches, updates, CSV
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
  findings, and 84 unit tests pass (parser, currencies, statements, updater) — but there was
  no emulator or phone available on the build machine, so layout and interaction
  haven't been exercised. Expect to nudge some spacing. The widget and the
  installer flow in particular are code I could only reason about, not run.
- **Updates are not unattended** — Android always asks you to confirm. See
  [Updating the app](#updating-the-app).
- Editing an existing entry isn't implemented yet — you delete and re-add.
- Categories are fixed to the built-in ten; adding your own isn't wired up yet.

---

## Coffee

Spendly is free, has no ads, and collects nothing. It was built to scratch a
personal itch — the spending apps I'd tried were fiddly enough that I stopped
bothering to log anything.

If it's made tracking your spending a little less of a chore and you feel like
it, you're very welcome to [buy me a coffee](https://buymeacoffee.com/casunoxd).
Completely optional — using the app, filing an issue, or sending a notification
format it fails to read are all just as helpful.
