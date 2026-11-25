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
