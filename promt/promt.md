# Curbox User App: qayta dizayn uchun 6 ta ketma-ket prompt

**Qanday ishlatiladi:** prompt'larni AI agentga (Claude Code, Cursor, Codex) **tartib bilan, bittadan** bering.
Har bir qism tugab, build o'tib, telefonda tekshirilgandan keyingina keyingisiga o'ting.
Har bir prompt'ning boshiga pastdagi **"Umumiy kontekst"** blokini qo'shib yuboring.

| # | Qism | Natija |
|---|---|---|
| 1 | Dizayn tizimi + eski ko'rinishni olib tashlash | Ranglar, shriftlar, kartalar, tugmalar; eski gradient dizayn o'chiriladi |
| 2 | Kirish oqimi | Splash, Admin/User tanlovi, chiroyli User login |
| 3 | Navigatsiya + Home | 5 bo'limli pastki menyu, bosh sahifa (aylana progress, kartalar) |
| 4 | Usage + Activity | Statistika grafiklari, faoliyat tarixi |
| 5 | Restrictions + bloklash ekrani + limit + Focus | Cheklovlar ro'yxati, "App restricted" ekrani, kunlik limit |
| 6 | Profile, bildirishnomalar, onboarding, tarjima, tozalash | Yakuniy sayqal, o'zbekcha tarjima, eski fayllarni o'chirish |

Faylning ikkinchi yarmida: **PC Admin Panel uchun A1–A6 prompt'lari** (pastda, "Curbox Admin Panel" bo'limi).

Manbalar: ChatGPT'dagi "CURBOX — USER APP" master prompt va undagi 7 ta namunaviy rasm (Paperpillar Screen Time, Grace, TimeZen, Android Digital Wellbeing, "App use" grafigi, "App Rules" ro'yxati, Android "Panel de control").

---

## Umumiy kontekst (har bir prompt boshiga qo'shing)

```text
PROJECT CONTEXT: CURBOX (read before doing anything)

Curbox is an Android screen time app (Kotlin) in this repository. A "Curbox for PC"
admin app (tools/pc-admin) manages linked phones over local WiFi. You are redesigning
the USER side of the Android app only. Do not touch the PC admin panel in this task.

Hard rules (from AGENTS.md, read it fully first):
- Kotlin, classic Views, Fragments, ViewBinding. NO Jetpack Compose, NO Navigation component.
  JVM target 1.8. Package root app/src/main/java/neth/iecal/curbox.
- Material Components 1.12 (Material 3 themes). Charts: MPAndroidChart 3.1.0 (already a
  dependency). Font: Inter (res/font/inter.xml). Do not add new dependencies without
  asking me first and saying why.
- Three processes. Settings only through DataStoreManager (utils/DataStore.kt). Room only
  through AppDatabase.getInstance(). Every new Settings field needs a default value.
- Room is at version 12 with fallbackToDestructiveMigration(). ANY entity change wipes
  the user's local data. STOP and ask me before changing the schema.
- AppBlockerService must never crash: keep feature calls inside the existing try/catch
  boundaries, log with CrashLogger.logNonFatalError, rethrow CancellationException,
  never walk accessibility node trees inside onAccessibilityEvent.
- Flavors: full, playstore, fdroid. The admin link (BuildConfig.SUPPORTS_ADMIN_LINK) and
  INTERNET exist ONLY in full. playstore and fdroid must keep building and keep working.
- All user facing text goes in res/values/strings.xml. Write for a sixth grade reader.
  No hyphens, en dashes or em dashes in displayed text.
- Never invent data. When there is no data, show an empty state. Never show a fake
  security or sync status. Never log passwords or tokens.

What already exists (reuse it, do not rewrite it):
- Launch: LoginActivity (in full it forwards to RoleSelectActivity) -> RoleSelectActivity
  (Admin / User) -> AdminLinkActivity (user sign in: address, login, password, consent
  dialog; later sign ins check a local SHA-256 hash) or AdminPanelActivity (WebView of the
  PC admin page). After sign in: FragmentActivity (old bottom nav: Usage, Focus,
  Reducers, Info).
- data/models/AdminLinkConfig: serverUrl, deviceId, deviceToken, deviceName, username,
  passwordHash, blockedApps, blockedSites. isLinked().
- blockers/AdminLinkBlocker: syncs every 10 s with POST /api/device/sync (returns
  username, blockedApps, blockedSites), blocks apps on TYPE_WINDOW_STATE_CHANGED with
  pressHome() + Toast, blocks websites from WebsiteObservation. Essential packages
  (launcher, keyboard, systemui, Curbox) are never blocked.
- Usage data in Room: AppUsageDao/AppUsageEntity, WebsiteStatsDao/WebsiteStatsEntity,
  FocusStatsDao. Focus mode: blockers/FocusModeBlocker. Blocking screens:
  ui/activity/WarningActivity with Constants.WARNING_SCREEN_MODE_*.
- The admin link does NOT yet support: daily limits, schedules, warning thresholds,
  restriction reasons, policy timestamps. Do not show these as real until a prompt adds
  them end to end (phone AND tools/pc-admin/CurboxAdmin.java).

DESIGN SYSTEM (same in every part)
Colors (light):  primary #5B6EE1, primary container #E7EAFC, secondary #8B9CF5,
                 background #F6F7FB, surface #FFFFFF, text #20243A, text secondary #858BA3,
                 outline #E6E8F0, success #25A56A, warning #F2A93B, error #E05260.
Colors (dark):   primary #8B9CF5, background #12141C, surface #1B1E29, text #ECEEF6,
                 text secondary #9AA0B8, outline #2A2E3D, same success/warning/error.
Shape:           cards 20dp, tiles 18dp, chips/pills fully rounded, buttons 16dp.
Spacing:         4dp grid; screen padding 20dp; gap between cards 12dp.
Type (Inter):    display 40sp bold (big time numbers), title 22sp bold, section 16sp
                 semibold, body 15sp, caption 13sp secondary color.
Elevation:       flat cards with 1dp outline in light, no outline in dark; at most one
                 soft shadow per screen. No decorative gradients.
Status pills:    Allowed = primary container + primary text; Blocked = error at 12%
                 + error text with a block icon; Time limited = warning at 14% + warning
                 text with a clock icon ("30m a day"); Scheduled = secondary at 14%.
Keep the school logo (drawable/logo, mipmap/ic_launcher) as the brand mark.

VERIFY at the end of every part: ./gradlew assembleFullDebug assemblePlaystoreDebug
assembleFdroidDebug (on this PC add: -I C:\Users\user\jdks\curbox-buildtools36.init.gradle).
Report what changed, what you checked, and what you could not check.
```

---

## 1-qism. Dizayn tizimi va eski ko'rinishni olib tashlash

> Bu qismda yangi ekran yo'q: faqat poydevor. Ranglar, shriftlar, komponentlar va eski gradient dizaynni olib tashlash.
> Rasm g'oyasi: Paperpillar (oq yumshoq kartalar, katta raqamlar, ingichka progress chiziqlari).

```text
PART 1 OF 6: DESIGN SYSTEM AND REMOVING THE OLD LOOK

Goal: build the shared design system from the DESIGN SYSTEM block, then move the user
facing screens that already exist onto it, and delete the old visuals they replace.

1. Theme and tokens
   - Add the colors to res/values/colors.xml and res/values-night/colors.xml under
     clear names (curbox_primary, curbox_surface, curbox_text_secondary, and so on).
   - Create Theme.Curbox.User (parent Theme.Material3.DayNight.NoActionBar) that maps
     colorPrimary, colorSurface, colorOnSurface, colorOutline, colorError etc. to the
     tokens and sets Inter as the font. Do NOT change Theme.Curbox, the old screens and
     WarningActivity still use it.
   - Text appearances: TextAppearance.Curbox.Display, .Title, .Section, .Body, .Caption.
   - Shape styles for cards (20dp), tiles (18dp), buttons (16dp).

2. Reusable components (ui/components/, classic Views, each with a layout and a small
   Kotlin class where behavior is needed):
   - CurboxCard style (MaterialCardView, outline in light, none in dark).
   - StatTile: icon in a tinted circle, big number, caption. (Grace style tiles.)
   - StatusPill: one view, four states (Allowed, Blocked, TimeLimited, Scheduled).
   - SectionHeader: title plus an optional "See all" action.
   - AppRow: app icon, name, package or subtitle, trailing StatusPill or value.
   - UsageRing: a custom View that draws a round progress ring with a big center time
     and a caption, animates from 0 on first show, and supports "no limit" (track
     only, no fill). (Android Digital Wellbeing ring.)
   - EmptyStateView: icon, title, one line of help text, optional button.
   - ConnectionChip: dot plus text for Connected, Syncing, Offline, Waiting for admin,
     Sync error. Colors from success/warning/error tokens.
   Add a debug only screen or preview layout so every component can be checked.

3. Move existing screens onto the system
   - RoleSelectActivity, AdminLinkActivity: switch to Theme.Curbox.User and the new
     components. Keep all behavior (consent dialog, local hash check, flavor guard).

4. Remove the old look (only what the steps above replaced)
   - Delete drawable/bg_brand_header.xml, bg_status_chip.xml, bg_icon_circle.xml,
     values/brand_colors.xml and any style only they used. Before deleting each file,
     grep for references; fix or move every reference first.
   - Do NOT delete old FragmentActivity screens yet; part 3 replaces them for users.

Done when: three flavors build, both screens look like the new system in light and dark,
and no file you deleted is still referenced. List every file you removed.
```

---

## 2-qism. Kirish oqimi: splash, Admin/User, User login

> Rasm g'oyalari: TimeZen (toza, katta sarlavhali boshlanish ekrani), ChatGPT maketidagi "Connected" chip.

```text
PART 2 OF 6: ENTRY FLOW

Goal: a calm, quick start that ends in a signed in user.

1. Splash
   - Show the Curbox logo, the name "Curbox" and the tagline "Your digital balance"
     with a short fade (under 600 ms). Use the system splash on Android 12+ by setting
     the theme's windowSplashScreen attributes; on older versions show the same look on
     the first screen. Do not add a dependency for this without asking me.

2. Role choice (RoleSelectActivity)
   - Two large cards: ADMIN and USER, each with an icon, a title and one line.
   - ADMIN: open a small info sheet first: "The admin panel runs on the computer. Open
     Curbox for PC there, or open it here with the admin login." with buttons "Open
     here" (existing AdminPanelActivity) and "Cancel". The phone app never gets admin
     powers by itself; the PC asks for the admin login.
   - USER: open the sign in screen. If already signed in, show "Signed in as NAME".

3. User sign in (AdminLinkActivity), keep the existing logic and add:
   - Fields: Computer address, Username, Password with show/hide toggle.
   - "Remember username" checkbox, stored in SharedPreferences "AppPreferences"
     (main process only). Never store the password.
   - Connection status line under the address that checks GET {address}/api/admin/state
     (public, no login) with a 3 s timeout while the user types (debounced 600 ms):
     "Computer found" (success), "Looking for the computer" (warning, while checking),
     "Computer not found. Check the address and WiFi." (error).
   - Errors inline under the right field: wrong login or password, unreachable
     computer, empty fields. Shake the card once on a wrong password.
   - Loading state on the button (spinner inside the button, button disabled).
   - Returning user: big greeting "Welcome back, NAME", only the password field.

Done when: first sign in, returning sign in, wrong password, computer off and no WiFi
all show the right state. Three flavors build. fdroid and playstore still open the old
password screen (LoginActivity) unchanged.
```

---

## 3-qism. Navigatsiya va Home sahifa

> Bu yerda eski Usage / Focus / Reducers / Info menyusi foydalanuvchi uchun yangi 5 bo'limli menyu bilan almashtiriladi.
> Rasm g'oyalari: Digital Wellbeing (aylana), Grace (rangli plitkalar), Paperpillar ("Most used apps" ro'yxati), ChatGPT maketi (salom, Connected, faol cheklovlar).

```text
PART 3 OF 6: NAVIGATION AND HOME DASHBOARD

Goal: signed in users land in a new five tab app; the old tabs are no longer shown to
them.

1. Shell
   - New UserMainActivity (full flavor only, declared in src/full/AndroidManifest.xml,
     guarded by BuildConfig.SUPPORTS_ADMIN_LINK) with a BottomNavigationView:
     Home, Usage, Restrictions, Activity, Profile. Keep each tab's fragment alive across
     switches the way FragmentActivity.showTab() does (hide/show, not replace).
     Fragments go in ui/fragments/user/.
   - AdminLinkActivity and RoleSelectActivity now open UserMainActivity after sign in.
   - Permission guides still use FragmentActivity with the "fragment" extra
     (AccessibilityGuide); keep that path working.
   - For signed in users the old Usage, Focus, Reducers and Info tabs are no longer
     reachable from the user flow. Do not delete their code: fdroid and playstore use it.

2. Home (most important screen), top to bottom:
   A. Greeting: "Hello, NAME", today's date, ConnectionChip (state from part 6 rules:
      until then, Connected when the last sync answer was 200 in the last 30 s,
      otherwise Offline).
   B. UsageRing: today's phone use from Room (AppUsageDao). With no admin daily limit
      (not supported yet) show the total time and "No daily limit set". Do not invent
      a limit.
   C. Four StatTiles in a 2x2 grid: Blocked apps (count from AdminLinkConfig.blockedApps
      that are installed), Blocked websites (count), Active schedules ("None" until
      supported), Protection (accessibility service on or off, from PermissionUtils).
      Tapping a tile opens the matching tab or the permission guide.
   D. Today's top apps: up to 5 AppRows with icon, name, time and a thin bar scaled to
      the top app. "See all" opens Usage.
   E. Active restrictions: up to 4 AppRows with StatusPill. "See all" opens Restrictions.
   F. Next reset: "Daily totals reset at midnight" with the time left.
   - Pull to refresh reloads data and asks AdminLinkBlocker to sync now
     (INTENT_ACTION_REFRESH_ADMIN_LINK broadcast with setPackage).
   - Empty states for no usage data and no restrictions.

Done when: Home shows real data in light and dark, every tile and "See all" works, a
brand new install shows clean empty states, and three flavors build.
```

---

## 4-qism. Usage statistikasi va Activity tarixi

> Rasm g'oyalari: "App use" (o'rtacha chiziqli ustunli grafik), TimeZen (kun tanlash qatori, rangli ilova chiziqlari), Android "Panel de control" (donut va ilovalar ro'yxati).

```text
PART 4 OF 6: USAGE ANALYTICS AND ACTIVITY HISTORY

1. Usage tab
   - Filter chips: Today, 7 days, 30 days.
   - Summary row: total time, daily average, change against the previous period
     (only when both periods have data).
   - Bar chart (MPAndroidChart BarChart): hours per day for 7/30 days, per hour for
     Today, rounded bars in primary, a dashed average line in error color labeled
     "avg". Animate on first show only. Readable axis labels, no grid clutter.
   - Donut (PieChart) of the top 5 apps plus "Other", with the legend as AppRows.
   - Full app list sorted by time, each AppRow with time and a thin bar.
   - Websites section from WebsiteStatsDao (top 5 domains), only when data exists.
   - Data comes from existing DAOs. Do the queries off the main thread. No sample data
     in release builds; debug builds may offer a "Fill with sample data" switch.

2. Activity tab
   - A timeline grouped by day with filters Today, Yesterday, Last 7 days. Every row has
     a time, an icon and one sentence.
   - Events you can show today without schema changes: app usage sessions (AppUsageDao),
     focus sessions (FocusStatsDao).
   - Events that need storage: "App blocked by admin", "Website blocked by admin", "New
     admin rules", "Synced", "Went offline". These need a new Room entity
     (ActivityEventEntity: id, type, timeMs, packageOrDomain, detail) with a size cap
     and cleanup in UsageStatsCleaner. THIS CHANGES THE ROOM SCHEMA: stop, explain to me
     that the version bump wipes local data on upgrade, and wait for my yes before
     adding it. Writes happen from the service process through
     AppDatabase.getInstance() inside the existing error boundaries.

Done when: charts match the Room data, every filter works, empty states show for days
with no data, and three flavors build.
```

---

## 5-qism. Restrictions, bloklash ekrani, kunlik limit va Focus

> Rasm g'oyalari: "App Rules" (ikonka, nom, paket, rangli holat yorlig'i, yuqorida "3 blocked, 2 time limited" xulosasi), Paperpillar ("Got it" tugmali ogohlantirish kartasi).
> Diqqat: kunlik limit va jadvallar uchun **PC server ham telefon ham** o'zgaradi.

```text
PART 5 OF 6: RESTRICTIONS, BLOCKED SCREEN, DAILY LIMIT, FOCUS

1. Restrictions tab
   - Summary line: "3 blocked, 2 time limited, 1 scheduled" (only the parts that exist).
   - Segmented control: Apps | Websites.
   - Apps: every installed launcher app as an AppRow with a StatusPill. Blocked ones
     first. Tap opens a bottom sheet with the details: status, reason (or "Set by your
     admin" when no reason was given), limit and schedule if any. Users can NOT change
     anything here; there are no edit buttons.
   - Websites: list of blocked domains with a globe icon; empty state when none.

2. Blocked app screen (replaces pressHome() + Toast in AdminLinkBlocker)
   - Add Constants.WARNING_SCREEN_MODE_ADMIN_BLOCK and show it in WarningActivity
     using the new design: logo, lock icon, "App restricted", the app name and icon,
     "Your admin restricted this app.", reason and time left if known, buttons
     "Back to home" (pressHome and finish) and "See details" (opens Restrictions with
     the app selected). Same screen for websites with "Website restricted".
   - Launch it from the service the same way AppBlocker does, keep the cooldown so it
     does not loop, keep essential packages unblockable, and keep everything inside
     the service's error boundaries. Never pretend the app crashed.

3. Daily limit and warnings (end to end, ask me before you start this step)
   - Server (tools/pc-admin/CurboxAdmin.java + admin.html): per device rules gain
     dailyLimitMinutes (0 = none), warningMinutes (default [60, 30, 10]), and an
     optional reason per blocked app. /api/device/sync returns them.
   - Phone: AdminLinkConfig gains the same fields with defaults (Gson safe). Used time
     comes from the existing app usage tracking. Show notifications at each warning
     threshold once per day: "60 minutes left today", "30 minutes left today", "Only 10
     minutes left. Please finish what you are doing." When the limit is reached, show
     the blocked screen for every app except essential ones, the phone dialer and
     emergency calls, and explain when it resets (midnight).
   - Home ring (part 3) now shows used and remaining against the real limit.

4. Focus mode (read only for users)
   - A card on Restrictions (and a Home tile when active): focus on or off, which apps
     are allowed or blocked, when it ends. Data from FocusModeBlocker state via
     settings. No controls that would let the user override admin rules.

Done when: blocking an app on the PC shows the new screen on the phone within 10 s,
the details sheet matches the PC, limit warnings fire once each, and three flavors
build. Say clearly which steps you tested on a real phone.
```

---

## 6-qism. Profile, bildirishnomalar, onboarding, tarjima va yakuniy tozalash

> Bu oxirgi qism: qolgan ekranlar, o'zbekcha tarjima va eski dizayn qoldiqlarini butunlay o'chirish.

```text
PART 6 OF 6: PROFILE, NOTIFICATIONS, CONNECTION, ONBOARDING, LANGUAGE, CLEANUP

1. Profile tab
   - Avatar with the username's first letter, username, device name, "Managed by your
     admin", ConnectionChip, last successful sync, app version.
   - Sections as rows: Account, Device, Permissions (each permission with on/off and a
     button to fix it), Privacy ("What your admin can see": app list and blocks only),
     About Curbox, Sign out.
   - Sign out only signs out on this phone (back to the role screen). It does NOT remove
     the admin link or lift blocks; say so in a confirm dialog: "Only your admin can
     remove this phone." If anti uninstall is on, explain what it does and that the
     admin removes it.

2. Connection status (everywhere the chip appears)
   - Store lastSyncMs and lastRulesChangeMs in AdminLinkConfig (defaults 0), written at
     most once a minute to avoid DataStore churn across processes.
   - States: Connected (sync OK under 30 s ago), Syncing, Offline (with "Last synced
     at TIME. Your last rules still apply."), Waiting for admin, Sync error.
   - Never claim new rules arrived when they did not.

3. Notification center (bell icon on Home)
   - Uses the ActivityEventEntity from part 4 (only if I approved it), filtered to:
     screen time warnings, app restricted, website restricted, new admin rules, limit
     reached, sync, protection. Read/unread dot, time, swipe to mark read.

4. Onboarding (first sign in only, 4 short pages with ViewPager2)
   - "Welcome to Curbox", "See your daily screen time", "See your admin's rules",
     "Connect with permission". Explain each Android permission before asking for it,
     then open the existing permission screens.

5. Language, dark theme, accessibility
   - Uzbek: add res/values-uz/strings.xml for every new string (I am asking for Uzbek
     explicitly). English stays the default. Respect the no hyphen rule in both.
   - Check every screen in dark mode, with font size 1.3x, and with TalkBack labels on
     all icons and tiles. Touch targets at least 48dp.

6. Final cleanup of the old user design
   - Run lint (UnusedResources) and delete layouts, drawables, strings and styles that
     no flavor uses any more. Keep everything fdroid and playstore still use.
   - Remove debug sample data switches from release paths.

Done when: three flavors build, unit tests pass (./gradlew testFullDebugUnitTest), the
whole user flow works in light and dark, in English and Uzbek, and you list every file
removed and every check you could not run.
```

---

# Curbox Admin Panel (Curbox for PC): 6 ta ketma-ket prompt

**Qanday ishlatiladi:** xuddi yuqoridagidek: bittadan, tartib bilan, har biriga pastdagi **"Admin umumiy kontekst"** blokini qo'shib.
Har bosqichdan keyin admin panelni brauzerda ochib, telefon va kompyuter bilan tekshiring.

**Eng muhim birinchi qadam:** loyiha hozir **git'da emas**, ya'ni zaxira nusxa yo'q. A1'dan oldin agent `git init` qilib, hozirgi holatni commit qilsin.

| # | Qism | Natija |
|---|---|---|
| A1 | Yangi dizayn, sidebar, xavfsizlik | `#5B6EE1` uslubi, Dashboard, kuchli parol xeshlari, sessiya muddati, bloklash |
| A2 | Qurilmalar, ilova va sayt boshqaruvi, admin tarixi | Qidiruv/filtr jadvali, ommaviy amallar, "barchasini bloklash", sayt tekshiruvi, audit log |
| A3 | Foydalanish statistikasi va hisobotlar | Qurilmalar vaqtini adminga yuboradi, haqiqiy Dashboard, Reports, CSV/PDF |
| A4 | Kunlik limit va jadvallar | Panel + telefon + kompyuter birga (User App 5-qismi bilan bir vaqtda) |
| A5 | Guruhlar, profillar, yangi qurilmani tasdiqlash | "8A sinf", "Study Mode", qoidalar ustuvorligi |
| A6 | Himoya kodi, bildirishnomalar, sozlamalar, tarjima | 6 xonali kod, ogohlantirishlar, zaxira nusxa, o'zbek/ingliz |

**Tavsiya etilgan umumiy tartib:** User 1–4 (tayyor) → A1 → A2 → A3 → A4 + User 5 → A5 → A6 + User 6.

Manbalar: ChatGPT'dagi "CURBOX V2 — PROFESSIONAL ADMIN PANEL" master prompt va undagi 4 ta rasm (Panze dashboard, IT Device Management jadvali, AppsAnywhere ilovalar jadvali, Atico ekran vaqti paneli).

---

## Admin umumiy kontekst (har bir A prompt boshiga qo'shing)

```text
PROJECT CONTEXT: CURBOX FOR PC, ADMIN SIDE (read before doing anything)

Curbox is a screen time system: an Android app (repo root, Kotlin) and "Curbox for PC"
(tools/pc-admin). You are upgrading the PC admin panel. Keep everything that works.

What exists in tools/pc-admin:
- CurboxAdmin.java: ONE Java 17 source file, JDK only (com.sun.net.httpserver, AWT tray,
  HttpURLConnection). No external libraries. It holds two classes:
  * CurboxAdmin: admin server on port 8787 (all interfaces today), Edge app window,
    tray (Open, Start with Windows, Change role, Quit), tiny JSON parser/writer.
  * PcUser: a managed PC. Setup page on 127.0.0.1:8789 (Admin or User, sign in,
    consent, one UAC prompt). Agent = Windows task "Curbox for PC Agent" running
    "Curbox for PC.exe --agent" as SYSTEM, status page on 127.0.0.1:8788. The agent
    syncs every 10 s, sends Start menu programs every 10 min, closes blocked .exe
    every 2 s (never Windows system processes or Curbox), and blocks domains in a
    marked section of the hosts file (domain, www., m.; plain domains only).
- admin.html: the admin single page app (vanilla JS, served by the server).
- user.html: the managed PC setup and status page.
- build-windows.ps1 (javac, jar, jpackage app image, IExpress "Curbox for PC.exe",
  zip; -Install installs here), install.ps1, Start Curbox Admin.bat (dev only).

Data (%APPDATA%\Curbox for PC\curbox-admin-data.json):
  { adminUser, adminHash, users:[{username, passwordHash, createdAt}],
    devices:[{id, user, platform:"android"|"windows", name, token,
              apps:[{p,l}], blockedApps:[], blockedSites:[], linkedAt, lastSeen, appVersion}] }
  Passwords are plain SHA-256 hex today (weak, fixed in A1).

Device API (phones and PC agents call these; keep them backward compatible):
  POST /api/device/login {username,password,deviceId,deviceName,platform} -> {token,username}
  POST /api/device/sync  header X-Device-Token, body {deviceName,appVersion,platform,apps?}
        -> {username, blockedApps, blockedSites}; 401 means "removed, lift all blocks".
Admin API (cookie cbx_admin, HttpOnly, SameSite=Strict):
  GET /api/admin/state, GET /api/admin/overview, POST /api/admin/{login,logout,rules,
  remove,password}, POST /api/admin/users/{add,delete,password}.

Android side (only touch it when a part says so; follow AGENTS.md):
  data/models/AdminLinkConfig.kt, utils/AdminLinkClient.kt, blockers/AdminLinkBlocker.kt
  (wired into services/AppBlockerService.kt), user app in ui/fragments/user/.
  Today phones send only their app list; they send NO usage time. The PC agent does
  not measure usage at all.

HARD RULES
- Backup first: if the folder is not a git repo, run git init and commit the current
  state before any change (ask me if anything looks risky).
- Keep CurboxAdmin.java a single Java 17 file with no dependencies (the dev .bat runs
  it with "java CurboxAdmin.java"). No npm, no CDN, no web fonts, no remote icons:
  the panel must work offline on a LAN. Draw icons as inline SVG.
- Backward compatible: an old data file must still load (new fields get defaults,
  migrate inside load()); old phones and old PC agents must keep syncing. Only ADD
  fields to API responses; never rename or remove one.
- Never trust an IP address as identity: devices use their token, the admin a session.
- Text that comes from devices (app names, device names) goes into the page only with
  textContent or DOM APIs, never innerHTML.
- Be honest per platform: if a control does not work on Android or on Windows, hide it
  there or mark it "Android only" / "Windows only". No sample data in the real UI.
- Displayed copy: plain English, sixth grade level, no hyphens or dashes (Uzbek comes
  in A6).
- Android changes follow AGENTS.md: three flavors, DataStore, service error
  boundaries, and STOP and ask before any Room schema change.

DESIGN (same language as the Android user app)
Colors: primary #5B6EE1, secondary #8B9CF5, background #F6F7FB, surface #FFFFFF,
text #20243A, text secondary #858BA3, outline #E6E8F0, success #25A56A,
warning #F2A93B (text #A86100), error #E05260. Dark mode: background #12141C,
surface #1B1E29, text #ECEEF6, text secondary #9AA0B8, outline #2A2E3D, primary #8B9CF5.
Layout: left sidebar 240px with icons (collapses to a top bar under 900px), top bar
with admin name, LAN address chip and Log out. Cards 20px radius, soft shadow, 4px grid.
Status pills: Allowed (primary soft), Blocked (error soft + block icon),
Time limited (warning soft + clock), Scheduled (secondary soft + calendar).
Online dot green, offline gray, with "Last seen 2 min ago".
Ideas from the reference images: stat cards row (Panze), device table with checkboxes
and a bulk action bar (IT Device Management), app table with search and
Block/Allow buttons (AppsAnywhere), daily screen time bars plus a recent events
column (Atico).

VERIFY AT THE END OF EVERY PART
1. javac --release 17 -Xlint:all CurboxAdmin.java compiles with no warnings.
2. Run a scratch server, never the real data or the real hosts file:
   CURBOX_PORT=18787 CURBOX_DATA_DIR=<temp dir> CURBOX_NO_WINDOW=1 java CurboxAdmin.java
   Agent tests: CURBOX_AGENT_DIR, CURBOX_HOSTS_FILE (a copy), CURBOX_AGENT_PORT=18788.
   Test every changed endpoint with curl, including wrong input and missing login.
3. Open the panel in a browser: light, dark, and a narrow window.
4. Android touched: ./gradlew assembleFullDebug assemblePlaystoreDebug assembleFdroidDebug
   (on this PC add -I C:\Users\user\jdks\curbox-buildtools36.init.gradle).
5. Run build-windows.ps1 (and -Install only when I ask).
Report: done, partly done, not done, limits, tests run and results, files changed.
```

---

## A1. Yangi dizayn, sidebar va xavfsizlik

> Eski to'q ko'k panel o'rniga yangi uslub, lekin **barcha mavjud funksiyalar saqlanadi**. Shu bilan birga xavfsizlikdagi zaif joylar tuzatiladi.
> Rasm g'oyalari: Panze (sidebar, statistika kartalari).

```text
PART A1 OF 6: REDESIGN, NAVIGATION SHELL, SECURITY HARDENING

0. Backup: git init and commit if this is not a repo yet.

1. New shell (admin.html)
   - Login screen in the new design: login, password with show/hide, error text.
   - Sidebar: Dashboard, Devices, Users, Settings (only pages that work; later parts
     add Apps, Websites, Screen time, Schedules, Groups, Reports, Activity log).
   - Hash routes: #/dashboard, #/devices, #/devices/<id>, #/users, #/settings.
   - Move EVERY existing feature into it: device list, device detail (apps with
     Block/Allow, blocked websites add/remove, Remove device with confirm), users
     (add, new password, delete with a list of the devices that will be cut off),
     change admin password, connect instructions with the LAN address(es).
2. Dashboard (real data only)
   - Stat cards: Total devices, Online, Offline, Users, Blocked apps (sum over
     devices), Blocked websites (sum).
   - Device status list (phones and computers, online first).
   - "Connect a device" card: current LAN address(es) and short steps for Android and
     for Windows. Never hardcode an IP.
3. Security (server)
   - Passwords: PBKDF2WithHmacSHA256, 16 byte random salt, 210000 iterations, stored
     as "pbkdf2$<iterations>$<salt b64>$<hash b64>". Old SHA-256 hashes still log in
     once and are replaced on that successful login (admin and users).
   - Sessions: random token, expire after 12 hours without use; logout removes it.
   - Login lockout: after 5 wrong tries for the same login, refuse for 5 minutes
     (admin login and device login), keep the small delay.
   - Listen only on private IPv4 addresses (site local) and loopback, not 0.0.0.0.
     Add tray items "Server status" and "Restart server" (re reads addresses).
   - Response headers: X-Content-Type-Options nosniff, X-Frame-Options DENY,
     Referrer-Policy no-referrer, a Content-Security-Policy that allows only 'self'
     (inline style and script allowed only if you keep them inline).
4. Do not change the device API.

Done when: an old data file logs in with admin/admin123 and gets migrated, every old
feature works in the new UI, curl proves lockout and session expiry, phones and the
PC agent still sync, and screenshots show light, dark and narrow layouts.
```

---

## A2. Qurilmalar, ilova va sayt boshqaruvi, admin tarixi

> Rasm g'oyalari: IT Device Management (belgilash katakchali jadval, ommaviy amallar), AppsAnywhere (ilovalar jadvali, qidiruv, tugmalar).

```text
PART A2 OF 6: DEVICES, APP CONTROL, WEBSITE CONTROL, ACTIVITY LOG

1. Devices page
   - Table: type icon (phone or computer), name, user, OS (Android / Windows and
     version when the device sends it), online dot, last seen, Curbox version.
   - Search, filter by type and by online state, sort by name or last seen.
   - Checkboxes and a bulk bar: apply website rules to the selected devices, remove.
   - Remove: confirm dialog that says management stops and blocks are lifted on the
     next sync; after removal show "Waiting for the device" until it syncs and gets
     401, or "Device is offline, it will release when it comes back".
2. Device detail tabs: Overview, Apps (or Programs on Windows), Websites, Activity.
   Overview shows what this platform supports (Android: apps and website paths;
   Windows: programs and whole domains only).
3. App control
   - Table with search, "Show blocked only", per row Block/Allow, multi select Block
     or Allow.
   - "Block all apps": blocks every listed app except essential ones (Android:
     launcher, dialer, settings, Curbox; Windows: the agent's ESSENTIAL list). Ask
     for confirmation and show what stays allowed.
   - New apps: when a sync reports an app that was not in the last list, mark it
     "New" for 24 hours, apply the device default ("Allow new apps" or "Block new
     apps", a per device setting, default Allow) and log it.
4. Website control
   - Server side validation and normalization of every site: lowercase, strip scheme
     and "www.", reject anything that is not a domain or domain/path (400 with a
     clear message). No duplicates.
   - Show "Whole domain" or "Path (phones only)" next to each rule.
   - Quick add categories (a short curated list in code): Social media, Video,
     Gaming. Apply to one device, to the selected devices, or to all devices.
5. Activity log (admin audit)
   - Append only JSON lines file next to the data (admin-activity.log, rotate at
     5 MB, keep 2 files). Each line: time, actor, action, target, result.
   - Record: admin login (ok and failed), user add/delete/password, admin password
     change, app block/unblock, site add/remove, device linked, device removed,
     new app found.
   - Activity log page with filters (Today, 7 days, all) and search.
6. Add "policyVersion" (a number that grows on every rule change) to the sync
   response. Old devices ignore it.

Done when: everything above works with one phone and one PC (or scratch agents),
invalid sites are refused, the log records each action once, and the device API stays
compatible.
```

---

## A3. Foydalanish statistikasi, Dashboard va hisobotlar

> Hozir qurilmalar adminga vaqt yubormaydi, shuning uchun avval ma'lumot oqimi quriladi, keyin grafiklar.
> Rasm g'oyasi: Atico (kunlik ekran vaqti ustunlari, o'ng tomonda hodisalar).

```text
PART A3 OF 6: USAGE FROM DEVICES, REAL DASHBOARD, REPORTS

1. Protocol (add only): device sync body may include
   "usage": [{"date":"YYYY-MM-DD","totalMs":n,"apps":[{"p":"pkg or exe","ms":n}]}]
   for today and yesterday, sent at most every 5 minutes. The server keeps 35 days per
   device in its own file (usage-<deviceId>.json) so the main data file stays small.
2. Android: AdminLinkBlocker adds usage from the existing Room data (AppUsageDao,
   same filters as ui/fragments/user/UsageRepository: no Curbox, no ignored apps, no
   synced web rows). No schema change. Inside the service error boundaries.
3. Windows: the SYSTEM agent cannot see the user's foreground window. Add a small
   helper that runs in the user's session at sign in (HKCU Run, "--tracker"), samples
   the foreground process every 5 s (PowerShell with a C# P/Invoke of
   GetForegroundWindow and GetWindowThreadProcessId, no new dependency), skips idle
   time (GetLastInputInfo over 2 min), and writes minute totals that the agent sends.
   If this cannot be done reliably, show "Usage is not available on computers yet"
   instead of zeros.
4. Dashboard: today's screen time per device, top apps across devices, a 7 day bar
   chart (inline SVG, no chart library), recent events column (from the activity
   log), and "No data yet" states.
5. Reports page: Today, 7 days, 30 days, custom range; by device or by user; daily
   bars, app breakdown, device comparison. Export CSV, and "Print or save as PDF"
   with a print stylesheet. Only real data.
6. Keep device totals and app totals apart; never add overlapping measurements.

Done when: a phone's usage appears in the panel within 5 minutes and matches the
phone's own Usage tab for today, CSV opens in Excel, and computers show either real
data or the honest "not available" message.
```

---

## A4. Kunlik limit va jadvallar (User App 5-qismi bilan birga)

> Limit va jadval faqat panelda emas, telefon va kompyuterda ham bajarilishi kerak. Shuning uchun bu bosqich User App'ning 5-qismi bilan bir vaqtda qilinadi.

```text
PART A4 OF 6: DAILY LIMITS AND SCHEDULES (do together with User App part 5)

1. Model (per device, later also from profiles in A5):
   dailyLimitMinutes (0 = none), warningMinutes (default [60,30,10]),
   schedules: [{id, name, days:[1..7], start:"HH:MM", end:"HH:MM" (may pass
   midnight), blockApps:[...], blockSites:[...], blockAllApps:bool, enabled}].
   Sent in the sync response (added fields).
2. Admin UI
   - Screen time page: presets 30m, 1h, 2h, 3h, custom; per device; shows used today
     and time left when A3 data exists.
   - Schedules page: weekly grid editor (days by hours), list with on/off switch,
     templates "Study mode (Mon to Fri 08:00 to 14:00)", "Sleep (every day 22:00 to
     07:00)". Warn about overlaps. Times are the device's local time.
3. Android (User App part 5, step 3): AdminLinkBlocker computes the active rules =
   base blocks + active schedules; daily limit uses today's tracked use; warning
   notifications once per threshold per day; at the limit show the blocked screen for
   all apps except essential ones, the dialer and emergency calls; reset at midnight.
4. Windows agent: schedules block programs and rewrite the hosts section at each
   start and end. Daily limit only if A3 tracking works; otherwise say so in the UI.
5. Show for each rule where it applies and whether the device has received it
   (policyVersion acknowledged in the next sync).

Done when: a schedule set on the panel blocks TikTok on a phone and a program on a PC
at the right minute and lifts it at the end, and limit warnings fire once each.
```

---

## A5. Guruhlar, profillar va yangi qurilmani tasdiqlash

> Maktab ssenariysi: o'qituvchi "8A sinf" guruhiga "Study Mode" profilini qo'llaydi.

```text
PART A5 OF 6: GROUPS, POLICY PROFILES, DEVICE APPROVAL

1. Groups: create, rename, delete; add or remove devices; a device can be in several
   groups. Devices page filters by group.
2. Profiles (rules engine): {name, enabled, blockedApps, blockedSites,
   dailyLimitMinutes, schedules, allowSites (Android only)}. Assign to devices,
   users or groups. Duplicate a profile.
3. Effective policy, deterministic and explained:
   blocks = union of the device's own rules + all assigned profiles;
   daily limit = the smallest non zero limit; schedules = all enabled ones.
   Device detail shows each active rule with its source ("Study Mode via Class 8A").
   Conflicts (for example a site both allowed and blocked) are listed; block wins.
4. Cross platform app names: profile rules can name an app once with its Android
   package and Windows exe (a small built in map for common apps such as TikTok,
   Telegram, YouTube, Chrome, plus "add custom").
5. Allow list for websites is Android only (block everything except listed sites, the
   way focus mode does it); say so in the UI.
6. Device approval: setting "Approve new devices" (default off, so today's behavior
   stays). When on, a new device login waits as "Pending" until the admin approves it
   on the Dashboard; the device shows "Waiting for your admin".

Done when: one profile applied to a group reaches every device in it, the device
detail explains each rule's source, and approval on/off both work.
```

---

## A6. Himoya kodi, bildirishnomalar, sozlamalar va tarjima

> Oxirgi bosqich, User App'ning 6-qismi bilan birga.

```text
PART A6 OF 6: PROTECTION CODE, NOTIFICATIONS, SETTINGS, LANGUAGE, POLISH

1. Six digit removal code
   - The admin sets it; stored with PBKDF2 like passwords; the panel only shows
     "Code is set" with the date. Lock for 15 minutes after 5 wrong tries.
   - Android: connect it to the existing anti uninstall password mode
     (utils/AntiUninstallManager, AntiUninstallConfig, a delay gated setting: follow
     AGENTS.md) so removing Curbox needs the code. Send a derived verifier, never the
     code itself.
   - Windows: "Change role" and signing out of the managed PC need the code. Say
     honestly that a Windows administrator account can still stop the agent.
2. Protection page: per device permission and service state, last check, sync
   state, and protection events from the activity log.
3. Notifications for the admin: bell with unread count; new device or approval
   request, device offline for over 10 minutes, new app found, limit reached, sync
   failed. Windows toast through the tray for the important ones. Preferences.
4. Settings page: admin account, network (addresses, port), start with Windows,
   tray behavior, backup (download the data as a file, warn that it contains password
   hashes) and restore (with confirmation), about.
5. Language: Uzbek and English for admin.html and user.html (inline dictionaries),
   follows the browser language, with a switch. No hyphens in either language.
6. Final pass: empty, loading and error states on every page, keyboard focus, color
   contrast, and the full verification list including build-windows.ps1.

Done when: the code blocks removal on a phone and role change on a PC, notifications
appear for each event type, backup and restore round trip, and the panel works in
both languages.
```
