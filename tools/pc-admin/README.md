# Curbox Admin (PC)

A small admin server for Windows (any OS with Java 17+ works). Phones running the
`full` flavor of Curbox link to it over the local WiFi. The admin can then see each
phone's apps and block apps and websites on it.

## Run

Double click `Start Curbox Admin.bat`. It finds a JDK (`JAVA_HOME`, then
`%USERPROFILE%\jdks\jdk-*`, then `PATH`), starts the server on port 8787 and opens
`http://localhost:8787`. Or run `java CurboxAdmin.java` from this folder.

A new data file starts with the admin account `admin` / `admin123` and one user
`anvarov` / `anvarov`. Change the admin password on the page (Admin account), and
add, delete or reset users under Users. Windows Firewall asks whether Java may use
the network the first time: allow it on **private** networks, or phones cannot
connect.

## Link a phone

1. On the phone open Curbox and tap **User**.
2. Type the address shown on the admin page, then a user's login and password.
3. Accept the consent dialog, then turn on Curbox's accessibility permission.

Later sign ins on that phone only ask for the password and check it on the phone.
Tapping **Admin** on a phone opens this admin page inside the app.

The phone syncs every 10 seconds and reports its app list every 5 minutes. Blocks
are cached on the phone, so they keep working while the PC is off. Removing the
phone on the admin page lifts its blocks.

## Manage a computer

Install Curbox for PC on the computer to manage, pick **User**, type the admin
address, a login and its password, and accept the consent. Windows asks for admin
permission once; that registers the agent (`Curbox for PC.exe --agent`) as the task
"Curbox for PC Agent", which runs as SYSTEM at start up and serves its status page
on `127.0.0.1:8788`. The agent:

- syncs every 10 seconds and sends the programs found in every Start menu every
  10 minutes (`p` is the exe file name, for example `telegram.exe`);
- closes blocked programs every 2 seconds (Windows system processes and Curbox
  itself are never closed);
- blocks websites in its own marked part of the hosts file (`domain`, `www.` and
  `m.`). Only plain domains are written; paths like `youtube.com/shorts` only work on
  phones. Browsers with their own secure DNS may skip the hosts file;
- lifts every block when the admin removes the computer.

Link data for the agent lives in `%ProgramData%\Curbox for PC`. The choice of
Admin or User is kept per Windows user in `%APPDATA%\Curbox for PC\mode.txt`; the
admin tray menu has "Change role". Test overrides: `CURBOX_PORT`,
`CURBOX_AGENT_PORT`, `CURBOX_SETUP_PORT`, `CURBOX_DATA_DIR`, `CURBOX_AGENT_DIR`,
`CURBOX_HOSTS_FILE`, `CURBOX_NO_WINDOW=1`.

## Notes

- Plain HTTP on the local network only. Do not expose port 8787 to the internet.
- Data lives in `curbox-admin-data.json` next to the script (ignored by git).
- Website blocking relies on Curbox's website usage tracking, which reads the URL
  bar of supported browsers (`hardcoded/BrowserUrlBarIds.kt`).
- API: `POST /api/device/login`, `POST /api/device/sync` (header `X-Device-Token`)
  for phones; `/api/admin/*` for the page, behind the admin login.

## Usage and reports

- Phones add `"usage": [{"date","totalMs","apps":[{"p","ms"}]}]` for today and
  yesterday to a sync at most every 5 minutes. The numbers use the same filters as
  the phone's Usage tab.
- On a managed computer the SYSTEM agent cannot see the window in front, so a small
  helper (`Curbox for PC.exe --tracker`) starts at every Windows sign in (the agent
  adds it to `HKLM\...\Run`). Every 5 seconds it counts the program in front, skips
  time with no keyboard or mouse use for 2 minutes, and saves once a minute to
  `%ProgramData%\Curbox for PC\usage\<user>.json`. The agent sends only days the
  helper measured. The helper needs read access to the install folder, so on a PC
  with several Windows accounts it may only run for the account that installed it.
- The server keeps 35 days per device in `usage\usage-<deviceId>.json` next to the
  data file. Device totals and app totals are stored apart and never added together.
- `GET /api/admin/usage?from=&to=&device=&user=` (at most 92 days) feeds the
  dashboard and the Reports page; `GET /api/admin/usage.csv` with the same filters
  downloads a UTF-8 CSV for Excel. Reports print with their own print layout.

## Daily limits and schedules

- Per device: `dailyLimitMinutes` (0 = none), `warningMinutes` (default 60, 30, 10)
  and up to 20 `schedules` ({id, name, days 1 = Monday to 7, start, end "HH:MM" in
  the device's local time, an end before the start runs past midnight, blockApps,
  blockSites, blockAllApps, enabled}). All are added to the sync response.
- `POST /api/admin/limits` ({deviceIds or all, dailyLimitMinutes and/or
  warningMinutes}), `POST /api/admin/schedules/save` (one device's whole list) and
  `POST /api/admin/schedules/add` ({deviceIds or all, schedule}).
- Each device gets a rules version on every change. Devices send the version they
  last received in their next sync, and the panel shows "Received" or "Waiting for
  the device".
- Phones: the limit counts the same use as the Usage tab; warnings fire once per
  threshold per day; at the limit, or in a "block all apps" schedule, everything is
  blocked except Curbox, the home screen, keyboard, calls, emergency, alarms and
  settings. Rules are checked every 30 seconds and when they change.
- Computers: schedules close programs and rewrite the hosts section at their start
  and end (checked every 30 seconds). "Block all" means every Start menu program.
  The daily limit only works where the usage helper runs; computers show no
  warnings.

## Groups, profiles and approval

- Groups (`POST /api/admin/groups/save`, the whole list) hold devices; a device can
  be in several groups.
- Profiles (`POST /api/admin/profiles/save`, the whole list) hold apps (each named
  once with its Android packages and Windows programs; a small built in list covers
  common apps), websites, an allow list (phones only), a daily limit, and either
  "always" or time windows. A profile is given to devices, users or groups.
- The effective rules of a device: blocks are its own rules plus every profile it
  gets; the daily limit is the smallest one set; schedules are all enabled ones;
  where a site is both allowed and blocked, blocked wins. The panel shows each rule
  with its source, like "Study Mode via group Class 8A". The sync response carries
  the effective rules, and their hash bumps the device's rules version when a
  profile or group changes.
- "Approve new devices" (Settings, off by default): a device that signs in for the
  first time gets `"pending": true` and no rules until the admin approves it on the
  Dashboard. Phones show "Waiting for your admin"; computers show it on their
  status page.

## Protection, notifications, settings and language

- Release code: five wrong tries lock that device out for 15 minutes
  (`CURBOX_RELEASE_LOCKOUT_SEC`). The panel shows the date it was set. The sync
  response carries `releaseVerifier` (the PBKDF2 hash, never the code): a phone can
  turn on uninstall protection that only the admin's code turns off (Protection
  screen, needs Android's device admin consent).
- Managed computers: the status page has "Leave management" (the admin's computer
  checks the code); choosing Admin in setup is refused while the computer is linked.
  A Windows administrator account can still stop the agent.
- Devices send a small `protection` report (service, permissions, uninstall
  protection, device owner; for computers the agent, usage helper and hosts file)
  shown on each device's Protection tab, and `failedSyncs` after failed tries.
- Notifications (the bell): new device or approval request, offline for over
  10 minutes (`CURBOX_OFFLINE_ALERT_SEC`), new app, daily limit reached, and many
  failed syncs. Each type can be turned off or also shown as a Windows message.
- Settings: start with Windows (installed app only), backup download
  (`/api/admin/backup`, holds password hashes) and restore (`/api/admin/restore`;
  the old file is kept as `curbox-admin-data.before-restore.json`).
- admin.html and user.html speak English and Uzbek: an inline dictionary follows
  the browser language, and a button switches it.

## Rules, activity log and release code

- Website rules are checked on the server: lowercase, no scheme, no `www.`, and
  only `domain` or `domain/path`. Anything else gets `400 {"error":"bad_site"}`.
  Path rules only work on phones; computers block whole domains.
- Essential apps (Android launcher, dialer, settings, keyboard, Curbox; the agent's
  `ESSENTIAL` Windows programs) are never stored as blocked.
- Apps a device reports after its first list are tagged new for 24 hours. Each
  device has a "new apps" setting (allow or block, default allow).
- `/api/device/sync` also returns `policyVersion` (grows on every rule change) and
  `releaseCodeSet`. Old clients ignore both.
- Removed devices wait in `removedDevices` until they sync once and get `401`.
- `admin-activity.log` (JSON lines, next to the data file) records sign ins, user
  and password changes, rule changes, links, removals, new apps and release
  attempts. At 5 MB it moves to `admin-activity.log.1`; only two files are kept.
- Release code: the admin sets six digits in Settings (stored as PBKDF2, never
  shown again). A phone sends it to `POST /api/device/release`; five wrong tries
  lock that device out for 5 minutes. On a normal phone this only ends management
  inside Curbox; Android still lets the owner uninstall the app. Only a phone made
  device owner on a fresh reset (`adb shell dpm set-device-owner
  neth.iecal.curbox/neth.iecal.curbox.receivers.AdminReceiver`) blocks uninstalling
  until the release.
