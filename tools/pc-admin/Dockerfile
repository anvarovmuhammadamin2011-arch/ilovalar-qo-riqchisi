# MBSI server in a container, for Render (see render.yaml at the repository root).
# Build context: tools/pc-admin.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY CurboxAdmin.java admin.html user.html logo.png favicon.ico ./
RUN mkdir -p classes/web \
 && javac --release 17 -encoding UTF-8 -d classes CurboxAdmin.java \
 && cp admin.html user.html logo.png favicon.ico classes/web/ \
 && jar --create --file mbsi-server.jar --main-class CurboxAdmin -C classes .

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/mbsi-server.jar ./
COPY server/mbsi.apk ./mbsi.apk
# /data is the persistent disk: accounts, devices and rules live there.
ENV CURBOX_DATA_DIR=/data \
    CURBOX_NO_WINDOW=1 \
    CURBOX_BIND_ALL=1
CMD mkdir -p "$CURBOX_DATA_DIR" \
 && cp -f /app/mbsi.apk "$CURBOX_DATA_DIR/mbsi.apk" \
 && CURBOX_PORT="${PORT:-8787}" exec java -Djava.awt.headless=true -Xmx256m -jar /app/mbsi-server.jar
