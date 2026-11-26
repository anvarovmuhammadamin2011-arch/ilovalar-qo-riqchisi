#!/usr/bin/env bash
# Builds the MBSI server on Linux. Run from tools/pc-admin: bash server/build.sh
# Downloads a JDK 17 once (checked against its published SHA-256) into $MBSI_HOME/jdk,
# so the server needs no Java installed system wide and no admin rights.
set -euo pipefail
cd "$(dirname "$0")/.."

MBSI_HOME="${MBSI_HOME:-$HOME/mbsi}"
JDK="$MBSI_HOME/jdk"
mkdir -p "$MBSI_HOME"

if [ ! -x "$JDK/bin/javac" ]; then
    echo "Downloading JDK 17..."
    api="https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse"
    read -r link sum < <(curl -fsSL "$api" | python3 -c '
import json, sys
p = json.load(sys.stdin)[0]["binary"]["package"]
print(p["link"], p["checksum"])')
    tmp="$MBSI_HOME/jdk.tar.gz"
    curl -fsSL -o "$tmp" "$link"
    echo "$sum  $tmp" | sha256sum -c -
    rm -rf "$JDK.new" && mkdir -p "$JDK.new"
    tar -xzf "$tmp" -C "$JDK.new" --strip-components=1
    rm -rf "$JDK" && mv "$JDK.new" "$JDK" && rm -f "$tmp"
fi
"$JDK/bin/java" -version 2>&1 | head -1

echo "Compiling..."
rm -rf build && mkdir -p build/classes/web
"$JDK/bin/javac" --release 17 -encoding UTF-8 -d build/classes CurboxAdmin.java
cp admin.html user.html logo.png favicon.ico build/classes/web/
"$JDK/bin/jar" --create --file build/mbsi-server.jar --main-class CurboxAdmin -C build/classes .
echo "Built build/mbsi-server.jar"
