#!/usr/bin/env bash
# Starts the MBSI server behind the HTTPS proxy. Run from tools/pc-admin: bash server/start.sh
# Settings come from the environment or a .env file next to CurboxAdmin.java:
#   CURBOX_PUBLIC_URL       https address devices use, e.g. https://mbsi.example.uz (required)
#   CURBOX_ADMIN_PASSWORD   admin password for the very first start (8 or more characters)
#   PORT                    port the proxy forwards to (default 8787)
#   MBSI_HOME               where the JDK and the data live (default ~/mbsi)
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

MBSI_HOME="${MBSI_HOME:-$HOME/mbsi}"
export CURBOX_DATA_DIR="${CURBOX_DATA_DIR:-$MBSI_HOME/data}"
export CURBOX_PORT="${CURBOX_PORT:-${PORT:-8787}}"
export CURBOX_NO_WINDOW=1
: "${CURBOX_PUBLIC_URL:?Set CURBOX_PUBLIC_URL, for example https://mbsi.example.uz}"
export CURBOX_PUBLIC_URL

mkdir -p "$CURBOX_DATA_DIR"
# The phone app offered at /download/mbsi.apk ships with each deploy.
if [ -f server/mbsi.apk ]; then
    cp -f server/mbsi.apk "$CURBOX_DATA_DIR/mbsi.apk"
fi

exec "$MBSI_HOME/jdk/bin/java" -Djava.awt.headless=true -Xmx256m -jar build/mbsi-server.jar
