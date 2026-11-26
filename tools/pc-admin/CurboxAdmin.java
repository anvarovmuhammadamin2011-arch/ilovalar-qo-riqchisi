import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.CheckboxMenuItem;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Curbox Admin: a small server that runs on the admin's computer.
 *
 * Phones running Curbox (full flavor) link to it over the local WiFi by logging
 * in with a user account the admin manages, report their installed apps, and
 * fetch the apps and websites the admin has blocked. The admin manages
 * everything from a web page served at http://localhost:8787.
 *
 * Run with a JDK 17 or newer:  java CurboxAdmin.java
 * Data is kept in curbox-admin-data.json in the working directory.
 */
public class CurboxAdmin {

    static final int PORT = envInt("CURBOX_PORT", 8787);
    static final long ONLINE_WINDOW_MS = 30_000L;
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    static final String SESSION_COOKIE = "cbx_admin";

    // Starting accounts, used when the data file has none yet.
    static final String DEFAULT_ADMIN_USER = "admin";
    static final String DEFAULT_ADMIN_PASSWORD = "admin123";
    static final String DEFAULT_USER = "anvarov";
    static final String DEFAULT_USER_PASSWORD = "anvarov";

    static final String APP_NAME = "Curbox for PC";

    // Set by the jpackage launcher, so it tells the installed Windows app apart
    // from "java CurboxAdmin.java" run by hand.
    static final String APP_PATH = System.getProperty("jpackage.app-path");
    static final boolean PACKAGED = APP_PATH != null;

    static final Path DIR = Paths.get("").toAbsolutePath();
    static final Path DATA_DIR = resolveDataDir();
    static final Path DATA = DATA_DIR.resolve("curbox-admin-data.json");
    static final SecureRandom RNG = new SecureRandom();
    static final Object LOCK = new Object();

    static String adminUser = "";
    static String adminHash = "";
    static final List<Map<String, Object>> users = new ArrayList<>();
    static final List<Map<String, Object>> devices = new ArrayList<>();
    // Admin session token -> last use; sessions end after this long without use.
    static final Map<String, Long> sessions = new ConcurrentHashMap<>();
    static final long SESSION_IDLE_MS = envInt("CURBOX_SESSION_TTL_SEC", 12 * 3600) * 1000L;
    // Login key ("admin:name" or "device:name") -> {failures, locked until ms}.
    static final Map<String, long[]> loginFailures = new ConcurrentHashMap<>();
    static final int MAX_LOGIN_FAILURES = 5;
    static final long LOCKOUT_MS = envInt("CURBOX_LOCKOUT_SEC", 300) * 1000L;
    static final int PBKDF2_ITERATIONS = 210_000;

    // Grows on every rule change, so devices can tell new rules from old ones.
    static long policyVersion = 1;
    // PBKDF2 hash of the six digit code that lets a device leave management; "" when unset.
    static String releaseCodeHash = "";
    static long releaseCodeSetAt = 0;
    // Wrong release codes lock a device out longer than wrong passwords.
    static final long RELEASE_LOCKOUT_MS = envInt("CURBOX_RELEASE_LOCKOUT_SEC", 900) * 1000L;
    // Devices the admin removed, kept until they sync once and learn they are released.
    static final List<Map<String, Object>> removedDevices = new ArrayList<>();
    static final long NEW_APP_MS = 24 * 3600_000L;
    static final long REMOVED_KEEP_MS = 30L * 24 * 3600_000L;

    static final Path ACTIVITY_LOG = DATA_DIR.resolve("admin-activity.log");
    static final Path ACTIVITY_LOG_OLD = DATA_DIR.resolve("admin-activity.log.1");
    static final long ACTIVITY_MAX_BYTES = 5L * 1024 * 1024;
    static final Object LOG_LOCK = new Object();

    /** Quick add website groups in the admin panel. Kept short on purpose. */
    static final Map<String, List<String>> SITE_CATEGORIES = new LinkedHashMap<>();
    static {
        SITE_CATEGORIES.put("social", List.of("facebook.com", "instagram.com", "tiktok.com", "x.com",
            "twitter.com", "snapchat.com", "threads.net", "vk.com", "ok.ru"));
        SITE_CATEGORIES.put("video", List.of("youtube.com", "twitch.tv", "netflix.com", "vimeo.com",
            "dailymotion.com", "kick.com"));
        SITE_CATEGORIES.put("gaming", List.of("roblox.com", "steampowered.com", "epicgames.com", "poki.com",
            "crazygames.com", "miniclip.com", "friv.com"));
    }

    /** A website rule that is not a domain or domain/path. */
    static final class BadSiteException extends Exception {
        private static final long serialVersionUID = 1L;
        final String site;

        BadSiteException(String site) {
            super("Bad site: " + site);
            this.site = site;
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> flags = Arrays.asList(args);
        // The protection agent on a managed PC (run by Windows as SYSTEM).
        if (flags.contains("--agent")) {
            PcUser.runAgent();
            return;
        }
        // Usage helper, started at Windows sign in for each user.
        if (flags.contains("--tracker")) {
            PcUser.runTracker();
            return;
        }
        // One time elevated step that registers the agent with Windows.
        int install = flags.indexOf("--install-agent");
        if (install >= 0) {
            System.exit(PcUser.installAgent(install + 1 < args.length ? args[install + 1] : ""));
            return;
        }

        // The installed app runs as a tray app; --desktop does the same from source.
        boolean desktopApp = PACKAGED || flags.contains("--desktop");
        // Started by Windows at sign in: serve phones, but do not open a window.
        boolean background = flags.contains("--background");

        Files.createDirectories(DATA_DIR);
        if (PACKAGED) redirectLogs(DATA_DIR, "curbox-for-pc.log");

        if (desktopApp) {
            String mode = PcUser.readMode();
            // Installs from before roles existed already hold admin data: keep them admin.
            if (!PcUser.modeFileExists() && Files.exists(DATA)) mode = PcUser.MODE_ADMIN;
            if (PcUser.MODE_USER.equals(mode)) {
                if (!background) PcUser.openUserWindow();
                return;
            }
            if (!PcUser.MODE_ADMIN.equals(mode)) {
                if (!background) PcUser.runSetup(null);
                return;
            }
        }
        startAdmin(desktopApp, !background);
    }

    /** Runs the admin server (phones and PCs link to it) and, on the desktop, the tray icon. */
    static void startAdmin(boolean desktopApp, boolean showWindow) throws IOException {
        load();
        if (!bindAdminServers()) {
            // Already running: a second launch just brings up the window.
            System.out.println(APP_NAME + " is already running on port " + PORT + ".");
            if (desktopApp && showWindow) openWindow();
            return;
        }

        System.out.println(APP_NAME + " is running. Data: " + DATA);
        System.out.println("Admin page: http://localhost:" + PORT);
        for (String address : lanAddresses()) {
            System.out.println("Device address: " + address);
        }

        startAlertChecks();
        if (desktopApp) startTray(showWindow);
    }

    static boolean alertsStarted = false;

    static synchronized void startAlertChecks() {
        if (alertsStarted) return;
        alertsStarted = true;
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "curbox-alerts");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(() -> {
            try {
                checkAlerts();
            } catch (Exception e) {
                System.out.println("Alert check failed: " + e);
            }
        }, 60, 60, TimeUnit.SECONDS);
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "curbox-network");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(() -> {
            try {
                refreshLanServers();
            } catch (Exception e) {
                System.out.println("Network check failed: " + e);
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    static final List<HttpServer> adminServers = new ArrayList<>();
    static final java.util.concurrent.ExecutorService ADMIN_EXECUTOR = Executors.newCachedThreadPool();

    /**
     * Listens on loopback and on this computer's private network addresses only, never
     * on every interface, so the panel is not offered to a public network. Returns
     * false when loopback is taken, which means Curbox for PC already runs here.
     */
    static synchronized boolean bindAdminServers() {
        List<InetAddress> addresses = new ArrayList<>();
        addresses.add(InetAddress.getLoopbackAddress());
        try {
            addresses.add(InetAddress.getByName("::1"));
        } catch (IOException ignored) {
        }
        addresses.addAll(privateAddresses());

        for (InetAddress address : addresses) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(address, PORT), 0);
                server.createContext("/", CurboxAdmin::handle);
                server.setExecutor(ADMIN_EXECUTOR);
                server.start();
                adminServers.add(server);
            } catch (BindException e) {
                if (adminServers.isEmpty()) return false;
                System.out.println("Could not listen on " + address.getHostAddress() + ": " + e.getMessage());
            } catch (IOException e) {
                System.out.println("Could not listen on " + address.getHostAddress() + ": " + e.getMessage());
            }
        }
        return !adminServers.isEmpty();
    }

    static synchronized void stopAdminServers() {
        for (HttpServer server : adminServers) server.stop(0);
        adminServers.clear();
    }

    /**
     * Laptops move between networks (school WiFi, a phone hotspot). Every 20 seconds the
     * server listens on new WiFi addresses and stops on ones that are gone, so phones and
     * the QR code keep working without "Restart server".
     */
    static synchronized void refreshLanServers() {
        if (adminServers.isEmpty()) return;
        Set<String> now = new LinkedHashSet<>();
        for (InetAddress a : privateAddresses()) now.add(a.getHostAddress());
        Set<String> bound = new LinkedHashSet<>();
        for (java.util.Iterator<HttpServer> it = adminServers.iterator(); it.hasNext(); ) {
            HttpServer server = it.next();
            InetAddress address = server.getAddress().getAddress();
            if (address.isLoopbackAddress()) continue;
            String host = address.getHostAddress();
            if (now.contains(host)) {
                bound.add(host);
            } else {
                server.stop(0);
                it.remove();
                System.out.println("Stopped listening on " + host + " (network changed).");
            }
        }
        for (String host : now) {
            if (bound.contains(host)) continue;
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), PORT), 0);
                server.createContext("/", CurboxAdmin::handle);
                server.setExecutor(ADMIN_EXECUTOR);
                server.start();
                adminServers.add(server);
                System.out.println("Device address: " + host + ":" + PORT + " (network changed)");
                TrayIcon icon = trayIcon;
                if (icon != null) icon.displayMessage(APP_NAME, "New network. Devices connect at " + host + ":" + PORT, TrayIcon.MessageType.INFO);
            } catch (IOException e) {
                System.out.println("Could not listen on " + host + ": " + e.getMessage());
            }
        }
    }

    /** IPv4 addresses on private ranges (10.x, 172.16 to 31.x, 192.168.x) of active adapters. */
    static List<InetAddress> privateAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** One line for the tray: where devices connect and how many are online. */
    static String statusText() {
        List<String> addresses = lanAddresses();
        long now = System.currentTimeMillis();
        int total;
        int online = 0;
        synchronized (LOCK) {
            total = devices.size();
            for (Map<String, Object> d : devices) {
                if (now - num(d, "lastSeen") < ONLINE_WINDOW_MS) online++;
            }
        }
        String where = addresses.isEmpty() ? "No network. Connect this computer to WiFi." : "Devices connect at " + String.join(", ", addresses) + ".";
        return "Running. " + where + " " + online + " of " + total + " devices online.";
    }

    static int envInt(String name, int fallback) {
        try {
            String value = System.getenv(name);
            return value == null || value.trim().isEmpty() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------- Windows shell

    static Path resolveDataDir() {
        String override = System.getenv("CURBOX_DATA_DIR");
        if (override != null && !override.trim().isEmpty()) return Paths.get(override.trim());
        String appData = System.getenv("APPDATA");
        if (PACKAGED && appData != null) return Paths.get(appData, APP_NAME);
        return DIR;
    }

    /** The installed app has no console, so its output goes to a log file next to its data. */
    static void redirectLogs(Path dir, String fileName) {
        try {
            Path log = dir.resolve(fileName);
            boolean append = Files.exists(log) && Files.size(log) < 1_000_000;
            PrintStream out = new PrintStream(new FileOutputStream(log.toFile(), append), true, "UTF-8");
            System.setOut(out);
            System.setErr(out);
        } catch (IOException ignored) {
        }
    }

    /** Opens the admin page in its own app window (Edge app mode), or the default browser. */
    static void openWindow() {
        openWindow("http://localhost:" + PORT);
    }

    static void openWindow(String url) {
        // Automated tests run the app without opening windows on the desktop.
        if ("1".equals(System.getenv("CURBOX_NO_WINDOW"))) return;
        List<String> edges = new ArrayList<>();
        for (String base : new String[]{System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")}) {
            if (base != null) edges.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
        }
        for (String edge : edges) {
            if (!Files.exists(Paths.get(edge))) continue;
            try {
                new ProcessBuilder(edge, "--app=" + url, "--window-size=1280,860").start();
                return;
            } catch (IOException e) {
                System.out.println("Could not start Edge: " + e);
            }
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            System.out.println("Could not open a browser: " + e);
        }
    }

    static void startTray(boolean showWindow) {
        boolean background = !showWindow;
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            if (!background) openWindow();
            return;
        }
        try {
            Image image;
            try (InputStream in = openStatic("logo.png")) {
                image = in == null ? null : ImageIO.read(in);
            }
            if (image == null) throw new IOException("logo.png missing");

            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open " + APP_NAME);
            open.addActionListener(e -> openWindow());
            menu.add(open);

            if (PACKAGED) {
                CheckboxMenuItem autostart = new CheckboxMenuItem("Start with Windows", isAutostartOn());
                autostart.addItemListener(e -> setAutostart(autostart.getState()));
                menu.add(autostart);
            }

            menu.addSeparator();
            TrayIcon icon = new TrayIcon(image, APP_NAME, menu);
            trayIcon = icon;

            MenuItem status = new MenuItem("Server status");
            status.addActionListener(e -> icon.displayMessage(APP_NAME, statusText(), TrayIcon.MessageType.INFO));
            menu.add(status);

            // Picks up a new WiFi or a changed address without quitting the app.
            MenuItem restart = new MenuItem("Restart server");
            restart.addActionListener(e -> {
                stopAdminServers();
                boolean started = bindAdminServers();
                icon.displayMessage(APP_NAME,
                    started ? "Server restarted. " + statusText() : "Could not restart the server. Quit and open the app again.",
                    started ? TrayIcon.MessageType.INFO : TrayIcon.MessageType.ERROR);
            });
            menu.add(restart);
            menu.addSeparator();

            // Back to the Admin or User choice, for a computer set up with the wrong role.
            MenuItem changeRole = new MenuItem("Change role");
            changeRole.addActionListener(e -> {
                PcUser.writeMode(PcUser.MODE_NONE);
                SystemTray.getSystemTray().remove(icon);
                stopAdminServers();
                PcUser.runSetup(null);
            });
            menu.add(changeRole);

            MenuItem quit = new MenuItem("Quit");
            quit.addActionListener(e -> {
                SystemTray.getSystemTray().remove(icon);
                stopAdminServers();
                System.exit(0);
            });
            menu.add(quit);

            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openWindow());
            SystemTray.getSystemTray().add(icon);

            if (!background) {
                openWindow();
                List<String> addresses = lanAddresses();
                icon.displayMessage(APP_NAME,
                    addresses.isEmpty()
                        ? "Running. Connect this computer to WiFi so phones can reach it."
                        : "Running. Phones connect at " + addresses.get(0),
                    TrayIcon.MessageType.INFO);
            }
        } catch (Exception e) {
            System.out.println("Tray icon failed: " + e);
            if (!background) openWindow();
        }
    }

    static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    static boolean isAutostartOn() {
        return runQuietly("reg", "query", RUN_KEY, "/v", APP_NAME) == 0;
    }

    static void setAutostart(boolean on) {
        if (APP_PATH == null) return;
        if (on) {
            runQuietly("reg", "add", RUN_KEY, "/v", APP_NAME, "/t", "REG_SZ",
                "/d", "\"" + APP_PATH + "\" --background", "/f");
        } else {
            runQuietly("reg", "delete", RUN_KEY, "/v", APP_NAME, "/f");
        }
    }

    static int runQuietly(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor();
        } catch (Exception e) {
            System.out.println("Command failed: " + e);
            return -1;
        }
    }

    // ---------------------------------------------------------------- routing

    static void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if (method.equals("GET")) {
                switch (path) {
                    case "/":
                    case "/index.html":
                        serveFile(ex, "admin.html", "text/html; charset=utf-8");
                        return;
                    case "/logo.png":
                        serveFile(ex, "logo.png", "image/png");
                        return;
                    case "/favicon.ico":
                        serveFile(ex, "favicon.ico", "image/x-icon");
                        return;
                    case "/api/admin/state":
                        adminState(ex);
                        return;
                    case "/download/mbsi.apk":
                        downloadApk(ex);
                        return;
                    case "/download/qr.png":
                        downloadQr(ex);
                        return;
                    case "/api/admin/overview":
                        if (requireAdmin(ex)) adminOverview(ex);
                        return;
                    case "/api/admin/activity":
                        if (requireAdmin(ex)) adminActivity(ex);
                        return;
                    case "/api/admin/backup":
                        if (requireAdmin(ex)) adminBackup(ex);
                        return;
                    case "/api/admin/usage":
                        if (requireAdmin(ex)) adminUsage(ex);
                        return;
                    case "/api/admin/usage.csv":
                        if (requireAdmin(ex)) adminUsageCsv(ex);
                        return;
                    default:
                        sendJson(ex, 404, error("not_found"));
                        return;
                }
            }

            if (method.equals("POST")) {
                switch (path) {
                    case "/api/device/login":
                        deviceLogin(ex);
                        return;
                    case "/api/device/sync":
                        deviceSync(ex);
                        return;
                    case "/api/admin/login":
                        adminLogin(ex);
                        return;
                    case "/api/admin/logout":
                        adminLogout(ex);
                        return;
                    case "/api/admin/rules":
                        if (requireAdmin(ex)) adminRules(ex);
                        return;
                    case "/api/admin/remove":
                        if (requireAdmin(ex)) adminRemove(ex);
                        return;
                    case "/api/admin/users/add":
                        if (requireAdmin(ex)) adminAddUser(ex);
                        return;
                    case "/api/admin/users/delete":
                        if (requireAdmin(ex)) adminDeleteUser(ex);
                        return;
                    case "/api/admin/users/password":
                        if (requireAdmin(ex)) adminUserPassword(ex);
                        return;
                    case "/api/admin/password":
                        if (requireAdmin(ex)) adminChangePassword(ex);
                        return;
                    case "/api/admin/sites/bulk":
                        if (requireAdmin(ex)) adminBulkSites(ex);
                        return;
                    case "/api/admin/device/settings":
                        if (requireAdmin(ex)) adminDeviceSettings(ex);
                        return;
                    case "/api/admin/release-code":
                        if (requireAdmin(ex)) adminReleaseCode(ex);
                        return;
                    case "/api/admin/notifications/read":
                        if (requireAdmin(ex)) adminNotificationsRead(ex);
                        return;
                    case "/api/admin/notifications/prefs":
                        if (requireAdmin(ex)) adminNotifyPrefs(ex);
                        return;
                    case "/api/admin/autostart":
                        if (requireAdmin(ex)) adminAutostart(ex);
                        return;
                    case "/api/admin/restore":
                        if (requireAdmin(ex)) adminRestore(ex);
                        return;
                    case "/api/admin/groups/save":
                        if (requireAdmin(ex)) adminSaveGroups(ex);
                        return;
                    case "/api/admin/profiles/save":
                        if (requireAdmin(ex)) adminSaveProfiles(ex);
                        return;
                    case "/api/admin/global-sites":
                        if (requireAdmin(ex)) adminGlobalSites(ex);
                        return;
                    case "/api/admin/settings":
                        if (requireAdmin(ex)) adminSettings(ex);
                        return;
                    case "/api/admin/devices/approve":
                        if (requireAdmin(ex)) adminApprove(ex);
                        return;
                    case "/api/admin/limits":
                        if (requireAdmin(ex)) adminLimits(ex);
                        return;
                    case "/api/admin/schedules/save":
                        if (requireAdmin(ex)) adminSaveSchedules(ex);
                        return;
                    case "/api/admin/schedules/add":
                        if (requireAdmin(ex)) adminAddSchedule(ex);
                        return;
                    case "/api/device/release":
                        deviceRelease(ex);
                        return;
                    default:
                        sendJson(ex, 404, error("not_found"));
                        return;
                }
            }

            sendJson(ex, 405, error("method_not_allowed"));
        } catch (BadSiteException e) {
            Map<String, Object> out = error("bad_site");
            out.put("site", limit(e.site, 200));
            sendJson(ex, 400, out);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, error("bad_request"));
        } catch (Exception e) {
            System.out.println("Request failed: " + e);
            sendJson(ex, 500, error("server_error"));
        } finally {
            ex.close();
        }
    }

    // ----------------------------------------------------------- phone routes

    static void deviceLogin(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String username = normalizeUsername(str(body, "username"));
        String password = str(body, "password");
        String deviceId = str(body, "deviceId").trim();
        String name = limit(str(body, "deviceName").trim(), 60);
        String platform = normalizePlatform(str(body, "platform"));
        if (deviceId.isEmpty() || deviceId.length() > 100) throw new IllegalArgumentException();

        String lockKey = "device:" + username;
        long lockedFor = lockedSeconds(lockKey);
        if (lockedFor > 0) {
            sendJson(ex, 429, lockedError(lockedFor));
            return;
        }

        String stored;
        synchronized (LOCK) {
            Map<String, Object> user = findIn(users, "username", username);
            stored = user == null ? "" : str(user, "passwordHash");
        }
        // Hashing is slow on purpose, so it runs outside the lock that device syncs share.
        if (!verifyPassword(password, stored)) {
            recordFailure(lockKey);
            slowDown();
            sendJson(ex, 403, error("wrong_login"));
            return;
        }
        clearFailures(lockKey);
        String upgraded = isLegacyHash(stored) ? hashPassword(password) : null;

        synchronized (LOCK) {
            if (upgraded != null) {
                Map<String, Object> user = findIn(users, "username", username);
                if (user != null && stored.equals(str(user, "passwordHash"))) user.put("passwordHash", upgraded);
            }
            long now = System.currentTimeMillis();
            Map<String, Object> device = findIn(devices, "id", deviceId);
            boolean isNew = device == null;
            if (device == null) {
                device = new LinkedHashMap<>();
                device.put("id", deviceId);
                device.put("apps", new ArrayList<>());
                device.put("blockedApps", new ArrayList<>());
                device.put("blockedSites", new ArrayList<>());
                device.put("linkedAt", now);
                // With approval on, a new device waits until the admin says yes.
                if (approveNewDevices) device.put("pending", true);
                devices.add(device);
            }
            String token = randomHex(16);
            device.put("user", username);
            device.put("platform", platform);
            device.put("name", name.isEmpty() ? ("windows".equals(platform) ? "Computer" : "Phone") : name);
            device.put("token", token);
            device.put("lastSeen", now);
            String os = limit(str(body, "osVersion").trim(), 40);
            if (!os.isEmpty()) device.put("osVersion", os);
            // Linking again after a removal starts fresh.
            removedDevices.removeIf(d -> deviceId.equals(d.get("id")));
            save();
            boolean pending = Boolean.TRUE.equals(device.get("pending"));
            logActivity(username, isNew && pending ? "device_pending" : "device_linked", str(device, "name"), "ok", device);
            if (isNew) {
                notifyAdmin("new_device", pending ? str(device, "name") + " is waiting for your approval."
                    : str(device, "name") + " was linked by " + username + ".", device);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", token);
            out.put("username", username);
            out.put("pending", pending);
            sendJson(ex, 200, out);
            System.out.println("Phone linked: " + device.get("name") + " (" + username + ")");
        }
    }

    static void deviceSync(HttpExchange ex) throws IOException {
        String token = header(ex, "X-Device-Token");
        Map<String, Object> body = readJsonObject(ex);

        synchronized (LOCK) {
            Map<String, Object> device = token.isEmpty() ? null : findIn(devices, "token", token);
            if (device == null) {
                // A removed device learns it is released here; remember that it did.
                Map<String, Object> removed = token.isEmpty() ? null : findIn(removedDevices, "token", token);
                if (removed != null && num(removed, "releasedAt") == 0) {
                    removed.put("releasedAt", System.currentTimeMillis());
                    save();
                    logActivity("system", "device_released", str(removed, "name"), "ok", removed);
                }
                sendJson(ex, 401, error("unlinked"));
                return;
            }
            boolean changed = false;
            long now = System.currentTimeMillis();

            String name = limit(str(body, "deviceName").trim(), 60);
            if (!name.isEmpty() && !name.equals(device.get("name"))) {
                device.put("name", name);
                changed = true;
            }
            String platform = str(body, "platform").trim();
            if (!platform.isEmpty()) device.put("platform", normalizePlatform(platform));
            String version = limit(str(body, "appVersion").trim(), 40);
            if (!version.isEmpty()) device.put("appVersion", version);
            String os = limit(str(body, "osVersion").trim(), 40);
            if (!os.isEmpty() && !os.equals(device.get("osVersion"))) {
                device.put("osVersion", os);
                changed = true;
            }

            Object apps = body.get("apps");
            if (apps instanceof List) {
                List<Object> cleaned = new ArrayList<>();
                for (Object item : (List<?>) apps) {
                    if (!(item instanceof Map) || cleaned.size() >= 1000) continue;
                    Map<?, ?> app = (Map<?, ?>) item;
                    String pkg = limit(String.valueOf(app.get("p")), 200).trim();
                    String label = limit(String.valueOf(app.get("l")), 100).trim();
                    if (pkg.isEmpty() || pkg.equals("null")) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("p", pkg);
                    entry.put("l", label.isEmpty() || label.equals("null") ? pkg : label);
                    cleaned.add(entry);
                }
                noteNewApps(device, cleaned, now);
                device.put("apps", cleaned);
                changed = true;
            }

            if (device.remove("offlineAlerted") != null) changed = true;
            // What protects the device: a few yes or no answers it read from its own system.
            if (body.get("protection") instanceof Map) {
                Map<String, Object> report = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) body.get("protection")).entrySet()) {
                    String key = String.valueOf(e.getKey());
                    if (key.matches("[a-zA-Z]{1,30}") && e.getValue() instanceof Boolean && report.size() < 12) report.put(key, e.getValue());
                }
                report.put("checkedAt", now);
                device.put("protection", report);
                changed = true;
            }
            // Devices count sync tries that failed in a row; many means a real problem.
            if (body.get("failedSyncs") instanceof Number && ((Number) body.get("failedSyncs")).longValue() >= 3) {
                notifyAdmin("sync_failed", str(device, "name") + " could not reach this computer "
                    + ((Number) body.get("failedSyncs")).longValue() + " times in a row before it got through.", device);
            }
            // The device repeats the rules version it last received, so the panel knows it has them.
            Object ack = body.get("policyVersion");
            if (ack instanceof Number && ((Number) ack).longValue() != num(device, "ackVersion")) {
                device.put("ackVersion", ((Number) ack).longValue());
                changed = true;
            }

            device.put("lastSeen", now);
            if (changed) save();
            storeUsage(str(device, "id"), body.get("usage"));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("username", device.get("user"));
            if (Boolean.TRUE.equals(device.get("pending"))) {
                // Not approved yet: no rules, and the device says it is waiting.
                out.put("pending", true);
                out.put("blockedApps", new ArrayList<>());
                out.put("blockedSites", new ArrayList<>());
                out.put("policyVersion", 0);
                sendJson(ex, 200, out);
                return;
            }
            Map<String, Object> rules = effective(device);
            String hash = policyHash(rules);
            if (!hash.equals(device.get("policyHash"))) {
                // Profiles and groups change many devices at once; this catches every one.
                device.put("policyHash", hash);
                markChanged(device);
                save();
            }
            rules.remove("explain");
            out.putAll(rules);
            out.put("policyVersion", rulesVersion(device));
            out.put("releaseCodeSet", !releaseCodeHash.isEmpty());
            // A slow PBKDF2 hash of the code, never the code: lets a phone check it for
            // uninstall protection while offline.
            out.put("releaseVerifier", releaseCodeHash);
            sendJson(ex, 200, out);
        }
    }

    // ----------------------------------------------------------- admin routes

    static void adminState(HttpExchange ex) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loggedIn", isAdmin(ex));
        sendJson(ex, 200, out);
    }

    static void adminLogin(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String username = normalizeUsername(str(body, "username"));
        String password = str(body, "password");
        String lockKey = "admin:" + username;
        long lockedFor = lockedSeconds(lockKey);
        if (lockedFor > 0) {
            sendJson(ex, 429, lockedError(lockedFor));
            return;
        }

        String stored;
        boolean nameMatches;
        synchronized (LOCK) {
            nameMatches = username.equals(adminUser);
            stored = adminHash;
        }
        if (!nameMatches || !verifyPassword(password, stored)) {
            recordFailure(lockKey);
            logActivity(limit(username, 40), "admin_login", "", "failed", null);
            slowDown();
            sendJson(ex, 403, error("wrong_login"));
            return;
        }
        clearFailures(lockKey);
        if (isLegacyHash(stored)) {
            String upgraded = hashPassword(password);
            synchronized (LOCK) {
                if (stored.equals(adminHash)) {
                    adminHash = upgraded;
                    save();
                }
            }
        }
        startSession(ex);
        logActivity(username, "admin_login", "", "ok", null);
        sendJson(ex, 200, ok());
    }

    static void adminChangePassword(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String current = str(body, "currentPassword");
        String next = str(body, "newPassword");
        String stored;
        synchronized (LOCK) {
            stored = adminHash;
        }
        if (!verifyPassword(current, stored)) {
            logActivity(actor(), "admin_password", "", "failed", null);
            slowDown();
            sendJson(ex, 403, error("wrong_password"));
            return;
        }
        if (next.length() < 4) {
            sendJson(ex, 400, error("too_short"));
            return;
        }
        String hashed = hashPassword(next);
        synchronized (LOCK) {
            adminHash = hashed;
            save();
        }
        // A new password ends every other admin session.
        String own = cookie(ex, SESSION_COOKIE);
        sessions.keySet().removeIf(token -> !token.equals(own));
        logActivity(actor(), "admin_password", "", "ok", null);
        sendJson(ex, 200, ok());
    }

    static void adminAddUser(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String username = normalizeUsername(str(body, "username"));
        String password = str(body, "password");
        if (!isValidUsername(username)) {
            sendJson(ex, 400, error("bad_username"));
            return;
        }
        if (password.length() < 4) {
            sendJson(ex, 400, error("too_short"));
            return;
        }
        synchronized (LOCK) {
            if (findIn(users, "username", username) != null || username.equals(adminUser)) {
                sendJson(ex, 409, error("exists"));
                return;
            }
            users.add(newUser(username, password));
            save();
        }
        logActivity(actor(), "user_add", username, "ok", null);
        sendJson(ex, 200, ok());
    }

    static void adminDeleteUser(HttpExchange ex) throws IOException {
        String username = normalizeUsername(str(readJsonObject(ex), "username"));
        synchronized (LOCK) {
            if (!users.removeIf(u -> username.equals(u.get("username")))) {
                sendJson(ex, 404, error("no_user"));
                return;
            }
            // Their devices lose the link, which lifts the blocks there too.
            for (Map<String, Object> device : new ArrayList<>(devices)) {
                if (username.equals(device.get("user"))) retireDevice(device, "user_deleted");
            }
            save();
        }
        logActivity(actor(), "user_delete", username, "ok", null);
        sendJson(ex, 200, ok());
    }

    static void adminUserPassword(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String username = normalizeUsername(str(body, "username"));
        String password = str(body, "password");
        if (password.length() < 4) {
            sendJson(ex, 400, error("too_short"));
            return;
        }
        synchronized (LOCK) {
            Map<String, Object> user = findIn(users, "username", username);
            if (user == null) {
                sendJson(ex, 404, error("no_user"));
                return;
            }
            user.put("passwordHash", hashPassword(password));
            save();
        }
        logActivity(actor(), "user_password", username, "ok", null);
        sendJson(ex, 200, ok());
    }

    static void adminLogout(HttpExchange ex) throws IOException {
        String token = cookie(ex, SESSION_COOKIE);
        if (!token.isEmpty()) sessions.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        sendJson(ex, 200, ok());
    }

    static void adminOverview(HttpExchange ex) throws IOException {
        long now = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (LOCK) {
            out.put("adminUser", adminUser);
            out.put("addresses", lanAddresses());
            out.put("policyVersion", policyVersion);
            out.put("releaseCodeSet", !releaseCodeHash.isEmpty());
            out.put("releaseCodeSetAt", releaseCodeSetAt);
            out.put("categories", SITE_CATEGORIES);
            out.put("groups", groups);
            out.put("profiles", profiles);
            out.put("knownApps", KNOWN_APPS);
            out.put("approveNewDevices", approveNewDevices);
            out.put("globalSites", globalSites);
            out.put("downloadUrls", downloadUrls());
            out.put("apkAvailable", Files.exists(apkFile()));
            List<Object> recent = new ArrayList<>(notifications.subList(Math.max(0, notifications.size() - 50), notifications.size()));
            Collections.reverse(recent);
            out.put("notifications", recent);
            out.put("unread", notifications.stream().filter(n -> !Boolean.TRUE.equals(n.get("read"))).count());
            out.put("notifyPrefs", prefsView());
            out.put("port", PORT);
            out.put("installed", PACKAGED);
            out.put("autostart", PACKAGED && isAutostartOn());
            out.put("dataFile", DATA.toString());
            List<Object> userList = new ArrayList<>();
            for (Map<String, Object> user : users) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("username", user.get("username"));
                u.put("createdAt", user.get("createdAt"));
                userList.add(u);
            }
            out.put("users", userList);
            List<Object> list = new ArrayList<>();
            for (Map<String, Object> device : devices) {
                Map<String, Object> d = new LinkedHashMap<>(device);
                d.remove("token");
                d.remove("newApps");
                d.put("online", now - num(device, "lastSeen") < ONLINE_WINDOW_MS);
                d.putIfAbsent("newAppPolicy", "allow");
                d.put("dailyLimitMinutes", num(device, "dailyLimitMinutes"));
                d.put("warningMinutes", warningsOf(device));
                d.put("schedules", schedulesOf(device));
                d.put("rulesVersion", rulesVersion(device));
                Map<String, Object> rules = effective(device);
                d.put("effective", rules);
                d.put("pending", Boolean.TRUE.equals(device.get("pending")));
                d.remove("offlineAlerted");
                d.remove("limitAlertedOn");
                d.remove("policyHash");
                d.put("received", num(device, "ackVersion") >= rulesVersion(device)
                    && policyHash(rules).equals(device.get("policyHash")));
                String platform = str(device, "platform");
                Map<String, Object> fresh = freshNewApps(device, now);
                List<Object> apps = new ArrayList<>();
                Object rawApps = device.get("apps");
                if (rawApps instanceof List) {
                    for (Object o : (List<?>) rawApps) {
                        if (!(o instanceof Map)) continue;
                        Map<String, Object> app = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) app.put(String.valueOf(e.getKey()), e.getValue());
                        String pkg = str(app, "p");
                        if (isEssentialApp(platform, pkg)) app.put("e", true);
                        Object since = fresh.get(pkg);
                        if (since != null) app.put("n", since);
                        apps.add(app);
                    }
                }
                d.put("apps", apps);
                list.add(d);
            }
            out.put("devices", list);
            List<Object> removed = new ArrayList<>();
            for (Map<String, Object> device : removedDevices) {
                Map<String, Object> r = new LinkedHashMap<>(device);
                r.remove("token");
                removed.add(r);
            }
            out.put("removed", removed);
        }
        sendJson(ex, 200, out);
    }

    /** Sets one device's blocked apps and/or websites. A missing list stays as it is. */
    static void adminRules(HttpExchange ex) throws IOException, BadSiteException {
        Map<String, Object> body = readJsonObject(ex);
        String deviceId = str(body, "deviceId");
        List<String> apps = body.containsKey("blockedApps") ? cleanList(body.get("blockedApps"), false) : null;
        List<String> sites = body.containsKey("blockedSites") ? cleanSites(body.get("blockedSites")) : null;
        long version;
        synchronized (LOCK) {
            Map<String, Object> device = findIn(devices, "id", deviceId);
            if (device == null) {
                sendJson(ex, 404, error("no_device"));
                return;
            }
            boolean changed = false;
            if (apps != null) {
                String platform = str(device, "platform");
                // Essential apps are never blocked, so a rule can never lock up a device.
                apps.removeIf(app -> isEssentialApp(platform, app));
                changed = applyApps(device, apps, actor());
            }
            if (sites != null) changed |= applySites(device, sites, actor());
            if (changed) {
                markChanged(device);
                save();
            }
            version = rulesVersion(device);
        }
        Map<String, Object> out = ok();
        out.put("policyVersion", version);
        sendJson(ex, 200, out);
    }

    /** Adds and/or removes websites on the chosen devices, or on all of them. */
    static void adminBulkSites(HttpExchange ex) throws IOException, BadSiteException {
        Map<String, Object> body = readJsonObject(ex);
        boolean all = Boolean.TRUE.equals(body.get("all"));
        List<String> ids = cleanList(body.get("deviceIds"), false);
        List<String> add = cleanSites(body.get("add"));
        List<String> remove = cleanSites(body.get("remove"));
        if (add.isEmpty() && remove.isEmpty()) {
            sendJson(ex, 400, error("nothing_to_do"));
            return;
        }
        int updated = 0;
        synchronized (LOCK) {
            for (Map<String, Object> device : devices) {
                if (!all && !ids.contains(str(device, "id"))) continue;
                List<String> sites = strings(device.get("blockedSites"));
                for (String site : add) {
                    if (!sites.contains(site)) sites.add(site);
                }
                sites.removeAll(remove);
                if (applySites(device, sites, actor())) {
                    markChanged(device);
                    updated++;
                }
            }
            if (updated > 0) save();
        }
        Map<String, Object> out = ok();
        out.put("updated", updated);
        sendJson(ex, 200, out);
    }

    /** Per device settings. For now: what happens to apps installed after linking. */
    static void adminDeviceSettings(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String deviceId = str(body, "deviceId");
        String policy = str(body, "newAppPolicy");
        if (!policy.equals("allow") && !policy.equals("block")) throw new IllegalArgumentException();
        synchronized (LOCK) {
            Map<String, Object> device = findIn(devices, "id", deviceId);
            if (device == null) {
                sendJson(ex, 404, error("no_device"));
                return;
            }
            if (!policy.equals(device.get("newAppPolicy"))) {
                device.put("newAppPolicy", policy);
                save();
                logActivity(actor(), "new_app_policy", policy, "ok", device);
            }
        }
        sendJson(ex, 200, ok());
    }

    /** Removes one device ("deviceId") or several ("deviceIds"). Each is released on its next sync. */
    static void adminRemove(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        List<String> ids = cleanList(body.get("deviceIds"), false);
        String one = str(body, "deviceId");
        if (!one.isEmpty()) ids.add(one);
        int removed = 0;
        synchronized (LOCK) {
            for (String id : ids) {
                Map<String, Object> device = findIn(devices, "id", id);
                if (device == null) continue;
                retireDevice(device, "removed");
                logActivity(actor(), "device_removed", str(device, "name"), "ok", device);
                removed++;
            }
            if (removed > 0) save();
        }
        Map<String, Object> out = ok();
        out.put("removed", removed);
        sendJson(ex, 200, out);
    }

    /** Sets or clears the six digit code a device needs to leave management. */
    static void adminReleaseCode(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        boolean clear = Boolean.TRUE.equals(body.get("clear"));
        String hashed = "";
        if (!clear) {
            String code = str(body, "code").trim();
            if (!code.matches("[0-9]{6}")) {
                sendJson(ex, 400, error("bad_code"));
                return;
            }
            if (isWeakCode(code)) {
                sendJson(ex, 400, error("weak_code"));
                return;
            }
            hashed = hashPassword(code);
        }
        synchronized (LOCK) {
            releaseCodeHash = hashed;
            releaseCodeSetAt = clear ? 0 : System.currentTimeMillis();
            save();
        }
        logActivity(actor(), clear ? "release_code_clear" : "release_code_set", "", "ok", null);
        sendJson(ex, 200, ok());
    }

    /** 111111, 123456 and 987654 style codes are too easy to guess. */
    static boolean isWeakCode(String code) {
        boolean same = code.chars().allMatch(c -> c == code.charAt(0));
        return same || "0123456789".contains(code) || "9876543210".contains(code);
    }

    // ------------------------------------------------------- device release

    /**
     * A linked device asks to leave management with the admin's six digit code. The
     * code is checked here, never on the device, and wrong tries lock that device out.
     */
    static void deviceRelease(HttpExchange ex) throws IOException {
        String token = header(ex, "X-Device-Token");
        String code = str(readJsonObject(ex), "code").trim();
        String deviceId;
        String stored;
        synchronized (LOCK) {
            Map<String, Object> device = token.isEmpty() ? null : findIn(devices, "token", token);
            if (device == null) {
                sendJson(ex, 401, error("unlinked"));
                return;
            }
            deviceId = str(device, "id");
            stored = releaseCodeHash;
        }
        if (stored.isEmpty()) {
            sendJson(ex, 409, error("no_code"));
            return;
        }
        String lockKey = "release:" + deviceId;
        long lockedFor = lockedSeconds(lockKey);
        if (lockedFor > 0) {
            sendJson(ex, 429, lockedError(lockedFor));
            return;
        }

        if (!code.matches("[0-9]{6}") || !verifyPassword(code, stored)) {
            recordFailure(lockKey, RELEASE_LOCKOUT_MS);
            synchronized (LOCK) {
                Map<String, Object> device = findIn(devices, "id", deviceId);
                if (device != null) logActivity(str(device, "user"), "release_attempt", str(device, "name"), "failed", device);
            }
            slowDown();
            long lockedNow = lockedSeconds(lockKey);
            if (lockedNow > 0) {
                sendJson(ex, 429, lockedError(lockedNow));
                return;
            }
            long[] entry = loginFailures.get(lockKey);
            Map<String, Object> out = error("wrong_code");
            out.put("attemptsLeft", MAX_LOGIN_FAILURES - (entry == null ? 0 : entry[0]));
            sendJson(ex, 403, out);
            return;
        }
        clearFailures(lockKey);

        synchronized (LOCK) {
            Map<String, Object> device = findIn(devices, "token", token);
            if (device == null) {
                sendJson(ex, 401, error("unlinked"));
                return;
            }
            Map<String, Object> retired = retireDevice(device, "release_code");
            retired.put("releasedAt", System.currentTimeMillis());
            save();
            logActivity(str(device, "user"), "device_released_by_code", str(device, "name"), "ok", device);
        }
        Map<String, Object> out = ok();
        out.put("released", true);
        sendJson(ex, 200, out);
    }

    // ------------------------------------------------------- rule helpers

    /** Call with LOCK held. Returns true when the list changed. */
    static boolean applyApps(Map<String, Object> device, List<String> apps, String actor) {
        List<String> before = strings(device.get("blockedApps"));
        if (before.equals(apps)) return false;
        Map<String, String> labels = new LinkedHashMap<>();
        Object rawApps = device.get("apps");
        if (rawApps instanceof List) {
            for (Object o : (List<?>) rawApps) {
                if (o instanceof Map) {
                    Map<?, ?> app = (Map<?, ?>) o;
                    labels.put(String.valueOf(app.get("p")), String.valueOf(app.get("l")));
                }
            }
        }
        List<String> added = new ArrayList<>(apps);
        added.removeAll(before);
        List<String> removed = new ArrayList<>(before);
        removed.removeAll(apps);
        device.put("blockedApps", apps);
        logChanges(actor, "app_block", added, labels, "apps", device);
        logChanges(actor, "app_unblock", removed, labels, "apps", device);
        return true;
    }

    /** Call with LOCK held. Returns true when the list changed. */
    static boolean applySites(Map<String, Object> device, List<String> sites, String actor) {
        List<String> before = strings(device.get("blockedSites"));
        if (before.equals(sites)) return false;
        List<String> added = new ArrayList<>(sites);
        added.removeAll(before);
        List<String> removed = new ArrayList<>(before);
        removed.removeAll(sites);
        device.put("blockedSites", sites);
        logChanges(actor, "site_add", added, Collections.emptyMap(), "websites", device);
        logChanges(actor, "site_remove", removed, Collections.emptyMap(), "websites", device);
        return true;
    }

    /** One line per item; a big change such as "Block all apps" is one summary line. */
    static void logChanges(String actor, String action, List<String> items, Map<String, String> labels,
                           String noun, Map<String, Object> device) {
        if (items.size() > 20) {
            logActivity(actor, action, items.size() + " " + noun, "ok", device);
            return;
        }
        for (String item : items) logActivity(actor, action, labels.getOrDefault(item, item), "ok", device);
    }

    /**
     * Call with LOCK held, before the new app list is stored. Apps missing from the
     * last list are marked new for a day and follow the device's new app setting.
     * The very first list a device sends is not "new".
     */
    static void noteNewApps(Map<String, Object> device, List<Object> current, long now) {
        Map<String, Object> fresh = freshNewApps(device, now);
        Object previous = device.get("apps");
        if (previous instanceof List && !((List<?>) previous).isEmpty()) {
            Set<String> known = new LinkedHashSet<>();
            for (Object o : (List<?>) previous) {
                if (o instanceof Map) known.add(String.valueOf(((Map<?, ?>) o).get("p")));
            }
            String platform = str(device, "platform");
            boolean blockNew = "block".equals(device.get("newAppPolicy"));
            List<String> blocked = strings(device.get("blockedApps"));
            boolean blockedChanged = false;
            int logged = 0;
            String firstNew = null;
            for (Object o : current) {
                Map<?, ?> app = (Map<?, ?>) o;
                String pkg = String.valueOf(app.get("p"));
                if (known.contains(pkg) || fresh.containsKey(pkg)) continue;
                fresh.put(pkg, now);
                boolean block = blockNew && !isEssentialApp(platform, pkg) && !blocked.contains(pkg);
                if (block) {
                    blocked.add(pkg);
                    blockedChanged = true;
                }
                if (logged++ < 50) {
                    logActivity("system", "new_app", String.valueOf(app.get("l")), block ? "blocked" : "allowed", device);
                }
                if (firstNew == null) firstNew = String.valueOf(app.get("l"));
            }
            if (firstNew != null) {
                notifyAdmin("new_app", logged == 1 ? "New app on " + str(device, "name") + ": " + firstNew
                    : logged + " new apps on " + str(device, "name") + ".", device);
            }
            if (blockedChanged) {
                device.put("blockedApps", blocked);
                markChanged(device);
            }
        }
        device.put("newApps", fresh);
    }

    /** The device's new apps seen in the last 24 hours, as package -> first seen. */
    static Map<String, Object> freshNewApps(Map<String, Object> device, long now) {
        Map<String, Object> fresh = new LinkedHashMap<>();
        Object raw = device.get("newApps");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getValue() instanceof Number && now - ((Number) e.getValue()).longValue() < NEW_APP_MS) {
                    fresh.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        }
        return fresh;
    }

    static final Set<String> ANDROID_ESSENTIAL = Set.of(
        "com.android.settings", "com.android.systemui", "com.android.phone", "com.android.dialer",
        "com.google.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom",
        "com.android.emergency", "com.google.android.apps.safetyhub", "com.android.contacts",
        "com.google.android.contacts", "com.samsung.android.contacts"
    );

    /** Apps a block never touches: Android launcher, dialer, settings, keyboard, Curbox; Windows system programs. */
    static boolean isEssentialApp(String platform, String app) {
        String p = app.toLowerCase(Locale.ROOT);
        if ("windows".equals(platform)) return PcUser.ESSENTIAL.contains(p);
        return p.startsWith("neth.iecal.curbox") || ANDROID_ESSENTIAL.contains(p)
            || p.contains("launcher") || p.contains("inputmethod") || p.endsWith(".keyboard");
    }

    /**
     * Call with LOCK held. Moves a device to the removed list, where it waits until it
     * syncs once and learns it was released.
     */
    static Map<String, Object> retireDevice(Map<String, Object> device, String reason) {
        long now = System.currentTimeMillis();
        devices.remove(device);
        String id = str(device, "id");
        Map<String, Object> r = new LinkedHashMap<>();
        for (String key : new String[]{"id", "name", "user", "platform", "token"}) r.put(key, device.get(key));
        r.put("removedAt", now);
        r.put("wasOnline", now - num(device, "lastSeen") < ONLINE_WINDOW_MS);
        r.put("releasedAt", 0L);
        r.put("reason", reason);
        removedDevices.removeIf(d -> id.equals(d.get("id")));
        removedDevices.add(r);
        // Released devices stay listed a week; ones that never came back, a month.
        removedDevices.removeIf(d -> {
            long released = num(d, "releasedAt");
            return (released > 0 && now - released > 7L * 24 * 3600_000L) || now - num(d, "removedAt") > REMOVED_KEEP_MS;
        });
        while (removedDevices.size() > 200) removedDevices.remove(0);
        return r;
    }

    static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null) out.add(String.valueOf(o));
            }
        }
        return out;
    }

    // ---------------------------------------------------------- activity log

    static String actor() {
        return adminUser;
    }

    /**
     * Appends one JSON line to admin-activity.log. At 5 MB the file moves to
     * admin-activity.log.1, so at most two files are kept. Never throws.
     */
    static void logActivity(String actor, String action, String target, String result, Map<String, Object> device) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("t", System.currentTimeMillis());
        e.put("actor", limit(actor == null ? "" : actor, 60));
        e.put("action", action);
        e.put("target", limit(target == null ? "" : target, 200));
        e.put("result", result);
        if (device != null) {
            e.put("device", str(device, "id"));
            e.put("deviceName", str(device, "name"));
        }
        byte[] line = (Json.write(e) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (LOG_LOCK) {
            try {
                if (Files.exists(ACTIVITY_LOG) && Files.size(ACTIVITY_LOG) + line.length > ACTIVITY_MAX_BYTES) {
                    Files.move(ACTIVITY_LOG, ACTIVITY_LOG_OLD, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.write(ACTIVITY_LOG, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                System.out.println("Could not write the activity log: " + ex);
            }
        }
    }

    /** GET /api/admin/activity?range=today|7d|all&q=text&device=id, newest first, at most 1000. */
    static void adminActivity(HttpExchange ex) throws IOException {
        Map<String, String> query = query(ex);
        String range = query.getOrDefault("range", "7d");
        String search = query.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
        String deviceId = query.getOrDefault("device", "");
        long now = System.currentTimeMillis();
        long since = 0;
        if (range.equals("today")) {
            since = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } else if (range.equals("7d")) {
            since = now - 7L * 24 * 3600_000L;
        }

        List<String> lines = new ArrayList<>();
        synchronized (LOG_LOCK) {
            for (Path file : List.of(ACTIVITY_LOG_OLD, ACTIVITY_LOG)) {
                if (Files.exists(file)) lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        }
        List<Object> entries = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && entries.size() < 1000; i--) {
            Object parsed;
            try {
                parsed = Json.parse(lines.get(i));
            } catch (RuntimeException e) {
                continue; // a half written line after a power cut
            }
            if (!(parsed instanceof Map)) continue;
            Map<?, ?> entry = (Map<?, ?>) parsed;
            Object t = entry.get("t");
            if (!(t instanceof Number) || ((Number) t).longValue() < since) continue;
            if (!deviceId.isEmpty() && !deviceId.equals(String.valueOf(entry.get("device")))) continue;
            if (!search.isEmpty()) {
                String text = (entry.get("actor") + " " + entry.get("action") + " " + entry.get("target") + " "
                    + entry.get("deviceName") + " " + entry.get("result")).toLowerCase(Locale.ROOT);
                if (!text.contains(search)) continue;
            }
            entries.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries);
        sendJson(ex, 200, out);
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                limit(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8), 200));
        }
        return out;
    }

    // ------------------------------------------- notifications, settings, backup (A6)

    static final List<Map<String, Object>> notifications = new ArrayList<>();
    static final Map<String, Object> notifyPrefs = new LinkedHashMap<>();
    static volatile TrayIcon trayIcon;
    static final long OFFLINE_ALERT_MS = envInt("CURBOX_OFFLINE_ALERT_SEC", 600) * 1000L;
    /** Event types, and whether each one also shows a Windows message by default. */
    static final Map<String, Boolean> NOTIFY_TYPES = new LinkedHashMap<>();
    static {
        NOTIFY_TYPES.put("new_device", true);
        NOTIFY_TYPES.put("offline", false);
        NOTIFY_TYPES.put("new_app", false);
        NOTIFY_TYPES.put("limit_reached", true);
        NOTIFY_TYPES.put("sync_failed", false);
    }

    /** {"new_device": {"panel": true, "toast": true}, ...}, with defaults for anything unset. */
    static Map<String, Object> prefsView() {
        Map<String, Object> out = new LinkedHashMap<>();
        NOTIFY_TYPES.forEach((type, toastDefault) -> {
            Map<String, Object> saved = notifyPrefs.get(type) instanceof Map ? mapsOf(List.of(notifyPrefs.get(type))).get(0) : Map.of();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("panel", !Boolean.FALSE.equals(saved.get("panel")));
            p.put("toast", saved.containsKey("toast") ? Boolean.TRUE.equals(saved.get("toast")) : toastDefault);
            out.put(type, p);
        });
        return out;
    }

    /** Call with LOCK held. Adds one admin notification and, if wanted, a Windows message. */
    static void notifyAdmin(String type, String text, Map<String, Object> device) {
        Map<String, Object> pref = mapsOf(List.of(prefsView().get(type))).get(0);
        if (!Boolean.TRUE.equals(pref.get("panel"))) return;
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", randomHex(6));
        n.put("t", System.currentTimeMillis());
        n.put("type", type);
        n.put("text", limit(text, 200));
        if (device != null) {
            n.put("device", str(device, "id"));
            n.put("deviceName", str(device, "name"));
        }
        n.put("read", false);
        notifications.add(n);
        while (notifications.size() > 200) notifications.remove(0);
        save();
        TrayIcon icon = trayIcon;
        if (icon != null && Boolean.TRUE.equals(pref.get("toast"))) {
            icon.displayMessage(APP_NAME, text, TrayIcon.MessageType.INFO);
        }
    }

    /**
     * Runs every minute: devices offline for over 10 minutes and devices that reached
     * today's limit. Each is told once: offline until the device is back, the limit once a day.
     */
    static void checkAlerts() {
        long now = System.currentTimeMillis();
        String today = LocalDate.now().toString();
        synchronized (LOCK) {
            for (Map<String, Object> device : devices) {
                if (Boolean.TRUE.equals(device.get("pending"))) continue;
                long lastSeen = num(device, "lastSeen");
                if (lastSeen > 0 && now - lastSeen > OFFLINE_ALERT_MS && !Boolean.TRUE.equals(device.get("offlineAlerted"))) {
                    device.put("offlineAlerted", true);
                    notifyAdmin("offline", str(device, "name") + " has been offline for over 10 minutes.", device);
                }
                long limit = num(effective(device), "dailyLimitMinutes");
                if (limit > 0 && !today.equals(device.get("limitAlertedOn"))) {
                    Map<String, Object> day = usageDay(str(device, "id"), today);
                    if (day != null && num(day, "totalMs") >= limit * 60_000L) {
                        device.put("limitAlertedOn", today);
                        notifyAdmin("limit_reached", str(device, "name") + " reached today's limit of " + limit + " minutes.", device);
                    }
                }
            }
        }
    }

    static void adminNotificationsRead(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        boolean all = Boolean.TRUE.equals(body.get("all"));
        List<String> ids = cleanList(body.get("ids"), false);
        synchronized (LOCK) {
            for (Map<String, Object> n : notifications) {
                if (all || ids.contains(str(n, "id"))) n.put("read", true);
            }
            save();
        }
        sendJson(ex, 200, ok());
    }

    static void adminNotifyPrefs(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        if (!(body.get("prefs") instanceof Map)) throw new IllegalArgumentException();
        Map<String, Object> raw = mapsOf(List.of(body.get("prefs"))).get(0);
        synchronized (LOCK) {
            for (String type : NOTIFY_TYPES.keySet()) {
                if (!(raw.get(type) instanceof Map)) continue;
                Map<String, Object> p = mapsOf(List.of(raw.get(type))).get(0);
                Map<String, Object> clean = new LinkedHashMap<>();
                clean.put("panel", !Boolean.FALSE.equals(p.get("panel")));
                clean.put("toast", Boolean.TRUE.equals(p.get("toast")));
                notifyPrefs.put(type, clean);
            }
            save();
        }
        sendJson(ex, 200, ok());
    }

    static void adminAutostart(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        if (!(body.get("on") instanceof Boolean)) throw new IllegalArgumentException();
        if (!PACKAGED) {
            sendJson(ex, 409, error("not_installed"));
            return;
        }
        setAutostart((Boolean) body.get("on"));
        Map<String, Object> out = ok();
        out.put("on", isAutostartOn());
        sendJson(ex, 200, out);
    }

    /** The whole data file as a download. It holds password hashes, so the page warns first. */
    static void adminBackup(HttpExchange ex) throws IOException {
        byte[] bytes;
        synchronized (LOCK) {
            bytes = Json.write(dataRoot()).getBytes(StandardCharsets.UTF_8);
        }
        logActivity(actor(), "backup", "", "ok", null);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"curbox-backup-" + LocalDate.now() + ".json\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Replaces all data with a backup. The current file is kept as
     * curbox-admin-data.before-restore.json, so a wrong restore can be undone by hand.
     */
    static void adminRestore(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        if (!(body.get("data") instanceof Map)) throw new IllegalArgumentException();
        Map<String, Object> data = mapsOf(List.of(body.get("data"))).get(0);
        if (str(data, "adminUser").isEmpty() || str(data, "adminHash").isEmpty()
            || !(data.get("users") instanceof List) || !(data.get("devices") instanceof List)) {
            sendJson(ex, 400, error("not_a_backup"));
            return;
        }
        synchronized (LOCK) {
            if (Files.exists(DATA)) {
                Files.copy(DATA, DATA_DIR.resolve("curbox-admin-data.before-restore.json"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = DATA_DIR.resolve("curbox-admin-data.json.tmp");
            Files.write(tmp, Json.write(data).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, DATA, StandardCopyOption.REPLACE_EXISTING);
            load();
            synchronized (USAGE_LOCK) {
                usageCache.clear();
            }
        }
        logActivity(actor(), "restore", "", "ok", null);
        sendJson(ex, 200, ok());
    }

    // --------------------------------------------- groups, profiles, approval (A5)

    static final List<Map<String, Object>> groups = new ArrayList<>();
    static final List<Map<String, Object>> profiles = new ArrayList<>();
    static boolean approveNewDevices = false;
    // Websites blocked on every device, also ones linked later.
    static final List<String> globalSites = new ArrayList<>();
    static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    static final Pattern EXE_NAME = Pattern.compile("[a-z0-9 ._()+-]{1,80}\\.exe");

    /** Common apps with their Android packages and Windows programs, so a profile names them once. */
    static final List<Map<String, Object>> KNOWN_APPS = new ArrayList<>();
    static {
        knownApp("TikTok", List.of("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), List.of("tiktok.exe"));
        knownApp("Instagram", List.of("com.instagram.android", "com.instagram.lite"), List.of());
        knownApp("YouTube", List.of("com.google.android.youtube", "com.google.android.apps.youtube.music"), List.of());
        knownApp("Telegram", List.of("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"), List.of("telegram.exe"));
        knownApp("WhatsApp", List.of("com.whatsapp", "com.whatsapp.w4b"), List.of("whatsapp.exe"));
        knownApp("Facebook", List.of("com.facebook.katana", "com.facebook.lite"), List.of());
        knownApp("Snapchat", List.of("com.snapchat.android"), List.of());
        knownApp("Discord", List.of("com.discord"), List.of("discord.exe"));
        knownApp("Roblox", List.of("com.roblox.client"), List.of("robloxplayerbeta.exe"));
        knownApp("Minecraft", List.of("com.mojang.minecraftpe"), List.of("minecraft.exe", "minecraftlauncher.exe"));
        knownApp("PUBG Mobile", List.of("com.tencent.ig", "com.pubg.imobile"), List.of("tslgame.exe"));
        knownApp("Free Fire", List.of("com.dts.freefireth", "com.dts.freefiremax"), List.of());
        knownApp("Steam", List.of(), List.of("steam.exe"));
        knownApp("Netflix", List.of("com.netflix.mediaclient"), List.of());
        knownApp("Chrome", List.of("com.android.chrome"), List.of("chrome.exe"));
    }

    static void knownApp(String name, List<String> android, List<String> windows) {
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("name", name);
        app.put("android", android);
        app.put("windows", windows);
        KNOWN_APPS.add(app);
    }

    static List<Map<String, Object>> mapsOf(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Replaces the group list: [{id?, name, deviceIds}]. Unknown devices are dropped. */
    static void adminSaveGroups(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        if (!(body.get("groups") instanceof List)) throw new IllegalArgumentException();
        List<Map<String, Object>> raw = mapsOf(body.get("groups"));
        if (raw.size() > 100) throw new IllegalArgumentException();
        synchronized (LOCK) {
            Set<String> known = new LinkedHashSet<>();
            for (Map<String, Object> d : devices) known.add(str(d, "id"));
            List<Map<String, Object>> cleaned = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            Set<String> names = new LinkedHashSet<>();
            for (Map<String, Object> g : raw) {
                String name = limit(str(g, "name").trim(), 40);
                if (name.isEmpty()) throw new IllegalArgumentException();
                if (!names.add(name.toLowerCase(Locale.ROOT))) {
                    sendJson(ex, 409, error("duplicate_name"));
                    return;
                }
                String id = str(g, "id");
                if (!id.matches("[a-f0-9]{8,32}") || !ids.add(id)) id = randomHex(6);
                ids.add(id);
                List<String> members = cleanList(g.get("deviceIds"), false);
                members.retainAll(known);
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("id", id);
                group.put("name", name);
                group.put("deviceIds", members);
                cleaned.add(group);
            }
            logListChanges("group", groups, cleaned);
            groups.clear();
            groups.addAll(cleaned);
            save();
        }
        sendJson(ex, 200, ok());
    }

    /** A checked profile. Apps name each app once with its Android packages and Windows programs. */
    static Map<String, Object> cleanProfile(Map<String, Object> in) throws BadSiteException {
        String name = limit(str(in, "name").trim(), 40);
        if (name.isEmpty()) throw new IllegalArgumentException();
        List<Object> apps = new ArrayList<>();
        for (Map<String, Object> a : mapsOf(in.get("apps"))) {
            if (apps.size() >= 100) break;
            String appName = limit(str(a, "name").trim(), 40);
            List<String> android = cleanList(a.get("android"), false);
            List<String> windows = new ArrayList<>();
            for (String exe : cleanList(a.get("windows"), false)) windows.add(exe.toLowerCase(Locale.ROOT));
            for (String pkg : android) if (!PACKAGE_NAME.matcher(pkg).matches()) throw new IllegalArgumentException();
            for (String exe : windows) if (!EXE_NAME.matcher(exe).matches()) throw new IllegalArgumentException();
            if (appName.isEmpty() || (android.isEmpty() && windows.isEmpty())) throw new IllegalArgumentException();
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("name", appName);
            app.put("android", android);
            app.put("windows", windows);
            apps.add(app);
        }
        String mode = str(in, "mode").equals("schedule") ? "schedule" : "always";
        List<Object> windowsOut = new ArrayList<>();
        for (Map<String, Object> w : mapsOf(in.get("windows"))) {
            if (windowsOut.size() >= 10) break;
            Map<String, Object> checked = new LinkedHashMap<>();
            Set<Long> days = new java.util.TreeSet<>();
            if (w.get("days") instanceof List) {
                for (Object d : (List<?>) w.get("days")) {
                    if (!(d instanceof Number) || ((Number) d).longValue() < 1 || ((Number) d).longValue() > 7) throw new IllegalArgumentException();
                    days.add(((Number) d).longValue());
                }
            }
            String start = str(w, "start");
            String end = str(w, "end");
            if (days.isEmpty() || !HHMM.matcher(start).matches() || !HHMM.matcher(end).matches() || start.equals(end)) {
                throw new IllegalArgumentException();
            }
            checked.put("days", new ArrayList<>(days));
            checked.put("start", start);
            checked.put("end", end);
            checked.put("blockAllApps", Boolean.TRUE.equals(w.get("blockAllApps")));
            windowsOut.add(checked);
        }
        if (mode.equals("schedule") && windowsOut.isEmpty()) throw new IllegalArgumentException();
        Object rawLimit = in.get("dailyLimitMinutes");
        long limit = rawLimit instanceof Number ? ((Number) rawLimit).longValue() : 0;
        if (limit < 0 || limit > 1440) throw new IllegalArgumentException();

        Map<String, Object> assign = new LinkedHashMap<>();
        Map<String, Object> rawAssign = in.get("assign") instanceof Map ? mapsOf(List.of(in.get("assign"))).get(0) : new LinkedHashMap<>();
        assign.put("devices", cleanList(rawAssign.get("devices"), false));
        List<String> userNames = new ArrayList<>();
        for (String u : cleanList(rawAssign.get("users"), false)) userNames.add(normalizeUsername(u));
        assign.put("users", userNames);
        assign.put("groups", cleanList(rawAssign.get("groups"), false));

        String id = str(in, "id");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.matches("[a-f0-9]{8,32}") ? id : randomHex(6));
        out.put("name", name);
        out.put("enabled", !Boolean.FALSE.equals(in.get("enabled")));
        out.put("apps", apps);
        out.put("sites", cleanSites(in.get("sites")));
        out.put("allowSites", cleanSites(in.get("allowSites")));
        out.put("dailyLimitMinutes", limit);
        out.put("mode", mode);
        out.put("windows", windowsOut);
        out.put("assign", assign);
        return out;
    }

    /** Replaces the profile list. */
    static void adminSaveProfiles(HttpExchange ex) throws IOException, BadSiteException {
        Map<String, Object> body = readJsonObject(ex);
        if (!(body.get("profiles") instanceof List)) throw new IllegalArgumentException();
        List<Map<String, Object>> raw = mapsOf(body.get("profiles"));
        if (raw.size() > 50) throw new IllegalArgumentException();
        List<Map<String, Object>> cleaned = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> p : raw) {
            Map<String, Object> profile = cleanProfile(p);
            if (!ids.add(str(profile, "id"))) profile.put("id", randomHex(6));
            cleaned.add(profile);
        }
        synchronized (LOCK) {
            logListChanges("profile", profiles, cleaned);
            profiles.clear();
            profiles.addAll(cleaned);
            save();
        }
        sendJson(ex, 200, ok());
    }

    /** One log line per added, changed or removed group or profile, by id. */
    static void logListChanges(String kind, List<Map<String, Object>> before, List<Map<String, Object>> after) {
        Map<String, Map<String, Object>> old = new LinkedHashMap<>();
        for (Map<String, Object> m : before) old.put(str(m, "id"), m);
        for (Map<String, Object> m : after) {
            Map<String, Object> previous = old.remove(str(m, "id"));
            if (previous == null) logActivity(actor(), kind + "_add", str(m, "name"), "ok", null);
            else if (!sameJson(previous, m)) logActivity(actor(), kind + "_edit", str(m, "name"), "ok", null);
        }
        for (Map<String, Object> m : old.values()) logActivity(actor(), kind + "_remove", str(m, "name"), "ok", null);
    }

    /** Adds and/or removes websites blocked on every device: {"add": [...], "remove": [...]}. */
    static void adminGlobalSites(HttpExchange ex) throws IOException, BadSiteException {
        Map<String, Object> body = readJsonObject(ex);
        List<String> add = cleanSites(body.get("add"));
        List<String> remove = cleanSites(body.get("remove"));
        if (add.isEmpty() && remove.isEmpty()) {
            sendJson(ex, 400, error("nothing_to_do"));
            return;
        }
        int changed = 0;
        synchronized (LOCK) {
            for (String site : add) {
                if (globalSites.contains(site) || globalSites.size() >= 1000) continue;
                globalSites.add(site);
                logActivity(actor(), "global_site_add", site, "ok", null);
                changed++;
            }
            for (String site : remove) {
                if (!globalSites.remove(site)) continue;
                logActivity(actor(), "global_site_remove", site, "ok", null);
                changed++;
            }
            // Every device's rules change; each one picks this up on its next sync.
            if (changed > 0) {
                for (Map<String, Object> device : devices) markChanged(device);
                save();
            }
        }
        Map<String, Object> out = ok();
        out.put("changed", changed);
        sendJson(ex, 200, out);
    }

    // ------------------------------------------------------- phone app download

    /** The phone app shared on the WiFi: mbsi.apk next to the data file. */
    static Path apkFile() {
        return DATA_DIR.resolve("mbsi.apk");
    }

    static List<String> downloadUrls() {
        List<String> out = new ArrayList<>();
        for (String address : lanAddresses()) out.add("http://" + address + "/download/mbsi.apk");
        return out;
    }

    /** Anyone on the WiFi may download the phone app; it holds no secrets. */
    static void downloadApk(HttpExchange ex) throws IOException {
        Path file = apkFile();
        if (!Files.exists(file)) {
            sendJson(ex, 404, error("no_apk"));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "application/vnd.android.package-archive");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"MBSI apk qoriqchi.apk\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(200, Files.size(file));
        try (OutputStream os = ex.getResponseBody()) {
            Files.copy(file, os);
        }
    }

    /** A QR code for the download link, for one of this computer's own addresses only. */
    static void downloadQr(HttpExchange ex) throws IOException {
        List<String> urls = downloadUrls();
        String host = query(ex).getOrDefault("host", "");
        String url = urls.isEmpty() ? "" : urls.get(0);
        for (String u : urls) if (u.equals("http://" + host + "/download/mbsi.apk")) url = u;
        if (url.isEmpty()) {
            sendJson(ex, 404, error("no_network"));
            return;
        }
        byte[] bytes = Qr.png(Qr.encode(url), 12);
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void adminSettings(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        if (!(body.get("approveNewDevices") instanceof Boolean)) throw new IllegalArgumentException();
        boolean on = (Boolean) body.get("approveNewDevices");
        synchronized (LOCK) {
            if (approveNewDevices != on) {
                approveNewDevices = on;
                save();
                logActivity(actor(), "approval_setting", on ? "on" : "off", "ok", null);
            }
        }
        sendJson(ex, 200, ok());
    }

    static void adminApprove(HttpExchange ex) throws IOException {
        String deviceId = str(readJsonObject(ex), "deviceId");
        synchronized (LOCK) {
            Map<String, Object> device = findIn(devices, "id", deviceId);
            if (device == null) {
                sendJson(ex, 404, error("no_device"));
                return;
            }
            if (Boolean.TRUE.equals(device.get("pending"))) {
                device.remove("pending");
                markChanged(device);
                save();
                logActivity(actor(), "device_approved", str(device, "name"), "ok", device);
            }
        }
        sendJson(ex, 200, ok());
    }

    /** Call with LOCK held. Profiles that apply to this device, each with how it got there. */
    static List<Map.Entry<Map<String, Object>, String>> profilesFor(Map<String, Object> device) {
        List<Map.Entry<Map<String, Object>, String>> out = new ArrayList<>();
        String id = str(device, "id");
        String user = str(device, "user");
        for (Map<String, Object> profile : profiles) {
            if (Boolean.FALSE.equals(profile.get("enabled"))) continue;
            Map<String, Object> assign = profile.get("assign") instanceof Map ? mapsOf(List.of(profile.get("assign"))).get(0) : new LinkedHashMap<>();
            String via = null;
            if (strings(assign.get("devices")).contains(id)) via = "this device";
            if (via == null && strings(assign.get("users")).contains(user)) via = "user " + user;
            if (via == null) {
                for (Map<String, Object> group : groups) {
                    if (strings(assign.get("groups")).contains(str(group, "id")) && strings(group.get("deviceIds")).contains(id)) {
                        via = "group " + str(group, "name");
                        break;
                    }
                }
            }
            if (via != null) out.add(Map.entry(profile, via));
        }
        return out;
    }

    /** The app keys a profile app means on this platform: Android packages or Windows programs. */
    static List<String> appKeys(Map<String, Object> app, String platform) {
        return strings(app.get("windows".equals(platform) ? "windows" : "android"));
    }

    /**
     * Call with LOCK held. The rules a device actually gets, and why:
     * blocks are the device's own rules plus every assigned profile; the daily limit is
     * the smallest one that is set; schedules are all enabled ones. Where a site is both
     * allowed and blocked, blocked wins.
     */
    static Map<String, Object> effective(Map<String, Object> device) {
        String platform = str(device, "platform");
        boolean android = !"windows".equals(platform);
        Map<String, List<String>> apps = new LinkedHashMap<>();
        Map<String, String> appLabels = new LinkedHashMap<>();
        Map<String, List<String>> sites = new LinkedHashMap<>();
        Map<String, List<String>> allow = new LinkedHashMap<>();
        List<Object> schedules = new ArrayList<>();
        List<Object> scheduleInfo = new ArrayList<>();
        String own = "Own rule";

        for (Object o : (List<?>) (device.get("apps") instanceof List ? device.get("apps") : List.of())) {
            if (o instanceof Map) appLabels.put(String.valueOf(((Map<?, ?>) o).get("p")), String.valueOf(((Map<?, ?>) o).get("l")));
        }
        for (String a : strings(device.get("blockedApps"))) apps.computeIfAbsent(a, k -> new ArrayList<>()).add(own);
        for (String s : strings(device.get("blockedSites"))) sites.computeIfAbsent(s, k -> new ArrayList<>()).add(own);
        for (String s : globalSites) sites.computeIfAbsent(s, k -> new ArrayList<>()).add("Every device");
        long limit = num(device, "dailyLimitMinutes");
        String limitSource = limit > 0 ? own : "";
        for (Object o : schedulesOf(device)) {
            if (!(o instanceof Map) || Boolean.FALSE.equals(((Map<?, ?>) o).get("enabled"))) continue;
            schedules.add(o);
            Map<String, Object> info = new LinkedHashMap<>(mapsOf(List.of(o)).get(0));
            info.put("source", own);
            scheduleInfo.add(info);
        }

        for (Map.Entry<Map<String, Object>, String> entry : profilesFor(device)) {
            Map<String, Object> profile = entry.getKey();
            String source = str(profile, "name") + " via " + entry.getValue();
            List<String> keys = new ArrayList<>();
            for (Map<String, Object> app : mapsOf(profile.get("apps"))) {
                for (String key : appKeys(app, platform)) {
                    keys.add(key);
                    appLabels.putIfAbsent(key, str(app, "name"));
                }
            }
            keys.removeIf(key -> isEssentialApp(platform, key));
            List<String> profileSites = strings(profile.get("sites"));
            if ("schedule".equals(profile.get("mode"))) {
                int i = 0;
                for (Map<String, Object> w : mapsOf(profile.get("windows"))) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", "p" + str(profile, "id") + "w" + (i++));
                    s.put("name", str(profile, "name"));
                    s.put("days", w.get("days"));
                    s.put("start", w.get("start"));
                    s.put("end", w.get("end"));
                    s.put("blockApps", keys);
                    s.put("blockSites", profileSites);
                    s.put("blockAllApps", Boolean.TRUE.equals(w.get("blockAllApps")));
                    s.put("enabled", true);
                    schedules.add(s);
                    Map<String, Object> info = new LinkedHashMap<>(s);
                    info.put("source", source);
                    scheduleInfo.add(info);
                }
            } else {
                for (String key : keys) apps.computeIfAbsent(key, k -> new ArrayList<>()).add(source);
                for (String site : profileSites) sites.computeIfAbsent(site, k -> new ArrayList<>()).add(source);
            }
            if (android) for (String site : strings(profile.get("allowSites"))) allow.computeIfAbsent(site, k -> new ArrayList<>()).add(source);
            long pLimit = num(profile, "dailyLimitMinutes");
            if (pLimit > 0 && (limit == 0 || pLimit < limit)) {
                limit = pLimit;
                limitSource = source;
            }
        }
        apps.keySet().removeIf(key -> isEssentialApp(platform, key));

        List<Object> conflicts = new ArrayList<>();
        for (String site : allow.keySet()) {
            if (sites.containsKey(site)) {
                conflicts.add(site + " is allowed by " + String.join(", ", allow.get(site)) + " and blocked by "
                    + String.join(", ", sites.get(site)) + ". Blocked wins.");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("blockedApps", new ArrayList<>(apps.keySet()));
        out.put("blockedSites", new ArrayList<>(sites.keySet()));
        List<String> allowList = new ArrayList<>(allow.keySet());
        allowList.removeAll(sites.keySet());
        out.put("allowSites", allowList);
        out.put("dailyLimitMinutes", limit);
        out.put("warningMinutes", warningsOf(device));
        out.put("schedules", schedules);

        Map<String, Object> explain = new LinkedHashMap<>();
        List<Object> appRows = new ArrayList<>();
        apps.forEach((key, from) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", appLabels.getOrDefault(key, key));
            row.put("sources", from);
            appRows.add(row);
        });
        List<Object> siteRows = new ArrayList<>();
        sites.forEach((site, from) -> siteRows.add(Map.of("site", site, "sources", from)));
        List<Object> allowRows = new ArrayList<>();
        allow.forEach((site, from) -> allowRows.add(Map.of("site", site, "sources", from)));
        explain.put("apps", appRows);
        explain.put("sites", siteRows);
        explain.put("allowSites", allowRows);
        explain.put("limit", Map.of("minutes", limit, "source", limitSource));
        explain.put("schedules", scheduleInfo);
        explain.put("conflicts", conflicts);
        out.put("explain", explain);
        return out;
    }

    /** The part of the effective rules a device receives; its hash tells when it changed. */
    static String policyHash(Map<String, Object> effective) {
        Map<String, Object> policy = new LinkedHashMap<>(effective);
        policy.remove("explain");
        return sha256(Json.write(policy));
    }

    // ------------------------------------------------------ limits and schedules (A4)

    static final List<Long> DEFAULT_WARNINGS = List.of(60L, 30L, 10L);
    static final Pattern HHMM = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
    static final int MAX_SCHEDULES = 20;

    /** Call with LOCK held. The device gets a new rules version, so the panel can tell when it has it. */
    static void markChanged(Map<String, Object> device) {
        policyVersion++;
        device.put("rulesVersion", policyVersion);
    }

    /** The rules version a device should confirm; at least 1, so a fresh device confirms too. */
    static long rulesVersion(Map<String, Object> device) {
        return Math.max(1, num(device, "rulesVersion"));
    }

    static List<Object> warningsOf(Map<String, Object> device) {
        Object raw = device.get("warningMinutes");
        return raw instanceof List ? new ArrayList<>((List<?>) raw) : new ArrayList<>(DEFAULT_WARNINGS);
    }

    static List<Object> schedulesOf(Map<String, Object> device) {
        Object raw = device.get("schedules");
        return raw instanceof List ? new ArrayList<>((List<?>) raw) : new ArrayList<>();
    }

    /** Sets the daily limit on the chosen devices, or on all of them. */
    static void adminLimits(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        boolean all = Boolean.TRUE.equals(body.get("all"));
        List<String> ids = cleanList(body.get("deviceIds"), false);
        // Either field may be left out to keep its current value.
        Object rawLimit = body.get("dailyLimitMinutes");
        Long limit = rawLimit instanceof Number ? ((Number) rawLimit).longValue() : null;
        if (limit != null && (limit < 0 || limit > 1440)) throw new IllegalArgumentException();
        List<Object> warnings = null;
        if (body.get("warningMinutes") instanceof List) {
            Set<Long> set = new java.util.TreeSet<>(Collections.reverseOrder());
            for (Object o : (List<?>) body.get("warningMinutes")) {
                if (!(o instanceof Number)) throw new IllegalArgumentException();
                long m = ((Number) o).longValue();
                if (m < 1 || m > 600) throw new IllegalArgumentException();
                set.add(m);
            }
            if (set.size() > 5) throw new IllegalArgumentException();
            warnings = new ArrayList<>(set);
        }
        if (limit == null && warnings == null) throw new IllegalArgumentException();
        int updated = 0;
        synchronized (LOCK) {
            for (Map<String, Object> device : devices) {
                if (!all && !ids.contains(str(device, "id"))) continue;
                boolean changed = false;
                if (limit != null && num(device, "dailyLimitMinutes") != limit) {
                    device.put("dailyLimitMinutes", limit);
                    changed = true;
                }
                if (warnings != null && !sameJson(warnings, warningsOf(device))) {
                    device.put("warningMinutes", warnings);
                    changed = true;
                }
                if (!changed) continue;
                markChanged(device);
                long current = num(device, "dailyLimitMinutes");
                logActivity(actor(), "limit_set", (current == 0 ? "No limit" : current + " min a day") + ", warnings at " + warningsText(device), "ok", device);
                updated++;
            }
            if (updated > 0) save();
        }
        Map<String, Object> out = ok();
        out.put("updated", updated);
        sendJson(ex, 200, out);
    }

    static String warningsText(Map<String, Object> device) {
        List<String> parts = new ArrayList<>();
        for (Object o : warningsOf(device)) parts.add(String.valueOf(((Number) o).longValue()));
        return String.join(", ", parts) + " min left";
    }

    /**
     * A checked copy of one schedule: days 1 (Monday) to 7, "HH:MM" start and end in the
     * device's local time (the end may be after midnight), apps and sites like normal rules.
     */
    static Map<String, Object> cleanSchedule(Object raw, String platform) throws BadSiteException {
        if (!(raw instanceof Map)) throw new IllegalArgumentException();
        @SuppressWarnings("unchecked")
        Map<String, Object> in = (Map<String, Object>) raw;
        String name = limit(str(in, "name").trim(), 40);
        if (name.isEmpty()) throw new IllegalArgumentException();
        Set<Long> days = new java.util.TreeSet<>();
        if (in.get("days") instanceof List) {
            for (Object d : (List<?>) in.get("days")) {
                if (!(d instanceof Number)) throw new IllegalArgumentException();
                long day = ((Number) d).longValue();
                if (day < 1 || day > 7) throw new IllegalArgumentException();
                days.add(day);
            }
        }
        if (days.isEmpty()) throw new IllegalArgumentException();
        String start = str(in, "start");
        String end = str(in, "end");
        if (!HHMM.matcher(start).matches() || !HHMM.matcher(end).matches() || start.equals(end)) {
            throw new IllegalArgumentException();
        }
        List<String> apps = cleanList(in.get("blockApps"), false);
        apps.removeIf(app -> isEssentialApp(platform, app));
        List<String> sites = cleanSites(in.get("blockSites"));
        boolean blockAll = Boolean.TRUE.equals(in.get("blockAllApps"));
        if (apps.isEmpty() && sites.isEmpty() && !blockAll) throw new IllegalArgumentException();

        String id = str(in, "id");
        if (!id.matches("[a-f0-9]{8,32}")) id = randomHex(6);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("days", new ArrayList<>(days));
        out.put("start", start);
        out.put("end", end);
        out.put("blockApps", apps);
        out.put("blockSites", sites);
        out.put("blockAllApps", blockAll);
        out.put("enabled", !Boolean.FALSE.equals(in.get("enabled")));
        return out;
    }

    /** Replaces one device's schedules (add, edit, switch on or off, delete). */
    static void adminSaveSchedules(HttpExchange ex) throws IOException, BadSiteException {
        Map<String, Object> body = readJsonObject(ex);
        String deviceId = str(body, "deviceId");
        if (!(body.get("schedules") instanceof List)) throw new IllegalArgumentException();
        List<?> raw = (List<?>) body.get("schedules");
        if (raw.size() > MAX_SCHEDULES) {
            sendJson(ex, 400, error("too_many"));
            return;
        }
        String platform;
        synchronized (LOCK) {
            Map<String, Object> device = findIn(devices, "id", deviceId);
            if (device == null) {
                sendJson(ex, 404, error("no_device"));
                return;
            }
            platform = str(device, "platform");
        }
        List<Object> cleaned = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : raw) {
            Map<String, Object> schedule = cleanSchedule(item, platform);
            if (!ids.add(str(schedule, "id"))) schedule.put("id", randomHex(6));
            cleaned.add(schedule);
        }
        synchronized (LOCK) {
            Map<String, Object> device = findIn(devices, "id", deviceId);
            if (device == null) {
                sendJson(ex, 404, error("no_device"));
                return;
            }
            List<Object> before = schedulesOf(device);
            if (!sameJson(before, cleaned)) {
                logScheduleChanges(before, cleaned, device);
                device.put("schedules", cleaned);
                markChanged(device);
                save();
            }
        }
        sendJson(ex, 200, ok());
    }

    /** Adds one schedule (a new copy each) to the chosen devices, or to all of them. */
    static void adminAddSchedule(HttpExchange ex) throws IOException, BadSiteException {
        Map<String, Object> body = readJsonObject(ex);
        boolean all = Boolean.TRUE.equals(body.get("all"));
        List<String> ids = cleanList(body.get("deviceIds"), false);
        int updated = 0;
        int full = 0;
        synchronized (LOCK) {
            for (Map<String, Object> device : devices) {
                if (!all && !ids.contains(str(device, "id"))) continue;
                List<Object> list = schedulesOf(device);
                if (list.size() >= MAX_SCHEDULES) {
                    full++;
                    continue;
                }
                Map<String, Object> schedule = cleanSchedule(body.get("schedule"), str(device, "platform"));
                schedule.put("id", randomHex(6));
                list.add(schedule);
                device.put("schedules", list);
                markChanged(device);
                logActivity(actor(), "schedule_add", str(schedule, "name"), "ok", device);
                updated++;
            }
            if (updated > 0) save();
        }
        Map<String, Object> out = ok();
        out.put("updated", updated);
        out.put("full", full);
        sendJson(ex, 200, out);
    }

    static void logScheduleChanges(List<Object> before, List<Object> after, Map<String, Object> device) {
        Map<String, Map<?, ?>> old = new LinkedHashMap<>();
        for (Object o : before) if (o instanceof Map) old.put(String.valueOf(((Map<?, ?>) o).get("id")), (Map<?, ?>) o);
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : after) {
            Map<?, ?> s = (Map<?, ?>) o;
            String id = String.valueOf(s.get("id"));
            seen.add(id);
            Map<?, ?> previous = old.get(id);
            if (previous == null) {
                logActivity(actor(), "schedule_add", String.valueOf(s.get("name")), "ok", device);
            } else if (!sameJson(previous, s)) {
                boolean onlySwitch = !Objects.equals(previous.get("enabled"), s.get("enabled"))
                    && sameJson(withoutEnabled(previous), withoutEnabled(s));
                logActivity(actor(), onlySwitch ? (Boolean.TRUE.equals(s.get("enabled")) ? "schedule_on" : "schedule_off") : "schedule_edit",
                    String.valueOf(s.get("name")), "ok", device);
            }
        }
        for (Map.Entry<String, Map<?, ?>> e : old.entrySet()) {
            if (!seen.contains(e.getKey())) logActivity(actor(), "schedule_remove", String.valueOf(e.getValue().get("name")), "ok", device);
        }
    }

    /** Stored numbers come back from JSON as doubles, so values are compared as written JSON. */
    static boolean sameJson(Object a, Object b) {
        return Json.write(a).equals(Json.write(b));
    }

    static Map<Object, Object> withoutEnabled(Map<?, ?> schedule) {
        Map<Object, Object> copy = new LinkedHashMap<>(schedule);
        copy.remove("enabled");
        return copy;
    }

    // ------------------------------------------------------------ usage (A3)

    static final Object USAGE_LOCK = new Object();
    static final int USAGE_KEEP_DAYS = 35;
    static final long DAY_MS = 24 * 3600_000L;
    static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    // Device id -> {"YYYY-MM-DD" -> {"totalMs": n, "apps": {key: ms}, "updatedAt": ms}}, read lazily.
    static final Map<String, Map<String, Object>> usageCache = new ConcurrentHashMap<>();

    /** One file per device next to the data file, so curbox-admin-data.json stays small. */
    static Path usageFile(String deviceId) {
        String safe = deviceId.matches("[A-Za-z0-9-]{1,64}") ? deviceId : sha256(deviceId).substring(0, 32);
        return DATA_DIR.resolve("usage").resolve("usage-" + safe + ".json");
    }

    /** Call with USAGE_LOCK held. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> usageDays(String deviceId) {
        return usageCache.computeIfAbsent(deviceId, id -> {
            Map<String, Object> days = new LinkedHashMap<>();
            Path file = usageFile(id);
            if (Files.exists(file)) {
                try {
                    Object root = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                    if (root instanceof Map && ((Map<String, Object>) root).get("days") instanceof Map) {
                        days.putAll((Map<String, Object>) ((Map<String, Object>) root).get("days"));
                    }
                } catch (Exception e) {
                    System.out.println("Could not read " + file + ": " + e);
                }
            }
            return days;
        });
    }

    /**
     * Stores what a device reported: [{"date":"YYYY-MM-DD","totalMs":n,"apps":[{"p":key,"ms":n}]}].
     * Each report replaces that day, because devices send whole day totals. The device
     * total and the app totals are kept apart and never added to each other.
     */
    static void storeUsage(String deviceId, Object raw) {
        if (!(raw instanceof List)) return;
        LocalDate today = LocalDate.now();
        long now = System.currentTimeMillis();
        Map<String, Map<String, Object>> incoming = new LinkedHashMap<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map) || incoming.size() >= 7) continue;
            Map<?, ?> day = (Map<?, ?>) item;
            String date = String.valueOf(day.get("date"));
            if (!ISO_DATE.matcher(date).matches()) continue;
            LocalDate parsed;
            try {
                parsed = LocalDate.parse(date);
            } catch (RuntimeException e) {
                continue;
            }
            // A device clock a day ahead is fine; anything older than we keep is not.
            if (parsed.isAfter(today.plusDays(1)) || parsed.isBefore(today.minusDays(USAGE_KEEP_DAYS - 1))) continue;
            Map<String, Object> apps = new LinkedHashMap<>();
            Object appList = day.get("apps");
            if (appList instanceof List) {
                for (Object a : (List<?>) appList) {
                    if (!(a instanceof Map) || apps.size() >= 300) continue;
                    Map<?, ?> app = (Map<?, ?>) a;
                    String key = limit(String.valueOf(app.get("p")).trim(), 200);
                    long ms = clampDay(app.get("ms"));
                    if (key.isEmpty() || key.equals("null") || ms <= 0) continue;
                    apps.put(key, ms);
                }
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("totalMs", clampDay(day.get("totalMs")));
            entry.put("apps", apps);
            entry.put("updatedAt", now);
            incoming.put(date, entry);
        }
        if (incoming.isEmpty()) return;

        synchronized (USAGE_LOCK) {
            Map<String, Object> days = usageDays(deviceId);
            days.putAll(incoming);
            String oldest = today.minusDays(USAGE_KEEP_DAYS - 1).toString();
            days.keySet().removeIf(date -> date.compareTo(oldest) < 0);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("deviceId", deviceId);
            root.put("days", new java.util.TreeMap<>(days));
            try {
                Path file = usageFile(deviceId);
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.out.println("Could not save usage: " + e);
            }
        }
    }

    static long clampDay(Object value) {
        if (!(value instanceof Number)) return 0;
        return Math.max(0, Math.min(DAY_MS, ((Number) value).longValue()));
    }

    /** A copy of one device's stored day, or null. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> usageDay(String deviceId, String date) {
        synchronized (USAGE_LOCK) {
            Object day = usageDays(deviceId).get(date);
            return day instanceof Map ? new LinkedHashMap<>((Map<String, Object>) day) : null;
        }
    }

    /** Parsed report filter: from/to dates (at most 92 days), device or user. */
    static final class UsageQuery {
        LocalDate from;
        LocalDate to;
        String device = "";
        String user = "";
    }

    static UsageQuery usageQuery(HttpExchange ex) {
        Map<String, String> q = query(ex);
        UsageQuery out = new UsageQuery();
        LocalDate today = LocalDate.now();
        try {
            out.to = q.containsKey("to") ? LocalDate.parse(q.get("to")) : today;
            out.from = q.containsKey("from") ? LocalDate.parse(q.get("from")) : out.to.minusDays(6);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad date");
        }
        if (out.from.isAfter(out.to)) throw new IllegalArgumentException("from after to");
        if (out.from.plusDays(91).isBefore(out.to)) out.from = out.to.minusDays(91);
        out.device = q.getOrDefault("device", "");
        out.user = q.getOrDefault("user", "");
        return out;
    }

    /** Devices that match the filter, as copies of the fields a report needs. */
    static List<Map<String, Object>> reportDevices(UsageQuery q) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Map<String, Object> device : devices) {
                if (!q.device.isEmpty() && !q.device.equals(device.get("id"))) continue;
                if (!q.user.isEmpty() && !q.user.equals(device.get("user"))) continue;
                Map<String, Object> d = new LinkedHashMap<>();
                for (String key : new String[]{"id", "name", "user", "platform"}) d.put(key, str(device, key));
                Map<String, String> labels = new LinkedHashMap<>();
                Object apps = device.get("apps");
                if (apps instanceof List) {
                    for (Object o : (List<?>) apps) {
                        if (o instanceof Map) labels.put(String.valueOf(((Map<?, ?>) o).get("p")), String.valueOf(((Map<?, ?>) o).get("l")));
                    }
                }
                d.put("labels", labels);
                out.add(d);
            }
        }
        return out;
    }

    /**
     * GET /api/admin/usage?from=&to=&device=&user=. Per device: daily totals and its apps;
     * across devices: daily totals and apps. Days without a report are 0 and listed in
     * "missing" so the page can tell no data from no use.
     */
    @SuppressWarnings("unchecked")
    static void adminUsage(HttpExchange ex) throws IOException {
        UsageQuery q = usageQuery(ex);
        List<String> dates = new ArrayList<>();
        for (LocalDate d = q.from; !d.isAfter(q.to); d = d.plusDays(1)) dates.add(d.toString());

        long[] dailyTotal = new long[dates.size()];
        Map<String, Map<String, Object>> allApps = new LinkedHashMap<>();
        List<Object> deviceOut = new ArrayList<>();
        for (Map<String, Object> device : reportDevices(q)) {
            String id = str(device, "id");
            String platform = str(device, "platform");
            Map<String, String> labels = (Map<String, String>) device.remove("labels");
            List<Long> daily = new ArrayList<>();
            Map<String, Long> apps = new LinkedHashMap<>();
            long total = 0;
            int reported = 0;
            for (int i = 0; i < dates.size(); i++) {
                Map<String, Object> day = usageDay(id, dates.get(i));
                long dayTotal = day == null ? 0 : num(day, "totalMs");
                if (day != null) reported++;
                // null: no report that day, which is not the same as no use.
                daily.add(day == null ? null : dayTotal);
                dailyTotal[i] += dayTotal;
                total += dayTotal;
                if (day != null && day.get("apps") instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) day.get("apps")).entrySet()) {
                        if (e.getValue() instanceof Number) apps.merge(String.valueOf(e.getKey()), ((Number) e.getValue()).longValue(), Long::sum);
                    }
                }
            }
            List<Object> appList = new ArrayList<>();
            apps.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(100).forEach(e -> {
                Map<String, Object> app = new LinkedHashMap<>();
                app.put("p", e.getKey());
                app.put("l", labelFor(platform, e.getKey(), labels));
                app.put("ms", e.getValue());
                appList.add(app);
            });
            // Across devices the same app adds up; each device measured its own time.
            apps.forEach((key, ms) -> {
                Map<String, Object> app = allApps.computeIfAbsent(platform + ":" + key, k -> {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("p", key);
                    a.put("l", labelFor(platform, key, labels));
                    a.put("platform", platform);
                    a.put("ms", 0L);
                    a.put("devices", 0L);
                    return a;
                });
                app.put("ms", num(app, "ms") + ms);
                app.put("devices", num(app, "devices") + 1);
            });
            device.put("daily", daily);
            device.put("totalMs", total);
            device.put("daysReported", reported);
            device.put("hasData", usageHasAnyData(id));
            device.put("apps", appList);
            deviceOut.add(device);
        }
        List<Object> appsOut = new ArrayList<>(allApps.values());
        appsOut.sort((a, b) -> Long.compare(num((Map<String, Object>) b, "ms"), num((Map<String, Object>) a, "ms")));
        if (appsOut.size() > 200) appsOut = new ArrayList<>(appsOut.subList(0, 200));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", q.from.toString());
        out.put("to", q.to.toString());
        out.put("dates", dates);
        List<Long> totals = new ArrayList<>();
        for (long t : dailyTotal) totals.add(t);
        out.put("dailyTotal", totals);
        out.put("devices", deviceOut);
        out.put("apps", appsOut);
        sendJson(ex, 200, out);
    }

    static boolean usageHasAnyData(String deviceId) {
        synchronized (USAGE_LOCK) {
            return !usageDays(deviceId).isEmpty();
        }
    }

    /** "chrome.exe" becomes "Google Chrome" when the device listed it; otherwise the key stays. */
    static String labelFor(String platform, String key, Map<String, String> labels) {
        String label = labels.get(key);
        if (label != null && !label.isEmpty() && !label.equals("null")) return label;
        if ("windows".equals(platform) && key.endsWith(".exe")) return key.substring(0, key.length() - 4);
        return key;
    }

    /** Same filter as /api/admin/usage, one row per device, day and app, plus a total row per device and day. */
    static void adminUsageCsv(HttpExchange ex) throws IOException {
        UsageQuery q = usageQuery(ex);
        StringBuilder csv = new StringBuilder("﻿");
        csv.append("Date,Device,User,Type,App,App name,Minutes\r\n");
        for (Map<String, Object> device : reportDevices(q)) {
            String id = str(device, "id");
            String platform = str(device, "platform");
            @SuppressWarnings("unchecked")
            Map<String, String> labels = (Map<String, String>) device.get("labels");
            String type = "windows".equals(platform) ? "Computer" : "Phone";
            for (LocalDate d = q.from; !d.isAfter(q.to); d = d.plusDays(1)) {
                Map<String, Object> day = usageDay(id, d.toString());
                if (day == null) continue;
                String prefix = csvCell(d.toString()) + "," + csvCell(str(device, "name")) + "," + csvCell(str(device, "user")) + "," + type + ",";
                csv.append(prefix).append(",Total screen time,").append(minutes(num(day, "totalMs"))).append("\r\n");
                if (day.get("apps") instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) day.get("apps")).entrySet()) {
                        if (!(e.getValue() instanceof Number)) continue;
                        String key = String.valueOf(e.getKey());
                        csv.append(prefix).append(csvCell(key)).append(',').append(csvCell(labelFor(platform, key, labels)))
                            .append(',').append(minutes(((Number) e.getValue()).longValue())).append("\r\n");
                    }
                }
            }
        }
        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"curbox-usage-" + q.from + "-to-" + q.to + ".csv\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String minutes(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 60000.0);
    }

    /** Quotes a CSV cell; a leading = + - @ is defused so Excel never runs it as a formula. */
    static String csvCell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------ admin auth

    static boolean isAdmin(HttpExchange ex) {
        String token = cookie(ex, SESSION_COOKIE);
        if (token.isEmpty()) return false;
        Long lastUse = sessions.get(token);
        long now = System.currentTimeMillis();
        if (lastUse == null) return false;
        if (now - lastUse > SESSION_IDLE_MS) {
            sessions.remove(token);
            return false;
        }
        sessions.put(token, now);
        return true;
    }

    static boolean requireAdmin(HttpExchange ex) throws IOException {
        if (isAdmin(ex)) return true;
        sendJson(ex, 401, error("login_required"));
        return false;
    }

    static void startSession(HttpExchange ex) {
        String token = randomHex(24);
        long now = System.currentTimeMillis();
        sessions.values().removeIf(lastUse -> now - lastUse > SESSION_IDLE_MS);
        sessions.put(token, now);
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
    }

    // ----------------------------------------------------------- persistence

    @SuppressWarnings("unchecked")
    static void load() {
        // Safe to call again, for example after switching this computer back to admin.
        users.clear();
        devices.clear();
        removedDevices.clear();
        groups.clear();
        profiles.clear();
        notifications.clear();
        notifyPrefs.clear();
        approveNewDevices = false;
        globalSites.clear();
        policyVersion = 1;
        releaseCodeHash = "";
        releaseCodeSetAt = 0;
        boolean hasUserList = false;
        if (Files.exists(DATA)) {
            try {
                Object root = Json.parse(new String(Files.readAllBytes(DATA), StandardCharsets.UTF_8));
                if (root instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) root;
                    adminUser = str(map, "adminUser");
                    adminHash = str(map, "adminHash");
                    Object userList = map.get("users");
                    if (userList instanceof List) {
                        hasUserList = true;
                        for (Object u : (List<Object>) userList) {
                            if (u instanceof Map) users.add((Map<String, Object>) u);
                        }
                    }
                    Object list = map.get("devices");
                    if (list instanceof List) {
                        for (Object d : (List<Object>) list) {
                            if (d instanceof Map) devices.add((Map<String, Object>) d);
                        }
                    }
                    Object removed = map.get("removedDevices");
                    if (removed instanceof List) {
                        for (Object d : (List<Object>) removed) {
                            if (d instanceof Map) removedDevices.add((Map<String, Object>) d);
                        }
                    }
                    policyVersion = Math.max(1, num(map, "policyVersion"));
                    releaseCodeHash = str(map, "releaseCodeHash");
                    releaseCodeSetAt = num(map, "releaseCodeSetAt");
                    groups.addAll(mapsOf(map.get("groups")));
                    profiles.addAll(mapsOf(map.get("profiles")));
                    approveNewDevices = Boolean.TRUE.equals(map.get("approveNewDevices"));
                    globalSites.addAll(strings(map.get("globalSites")));
                    notifications.addAll(mapsOf(map.get("notifications")));
                    if (map.get("notifyPrefs") instanceof Map) notifyPrefs.putAll(mapsOf(List.of(map.get("notifyPrefs"))).get(0));
                }
            } catch (Exception e) {
                System.out.println("Could not read " + DATA + ": " + e);
            }
        }

        boolean seeded = false;
        if (adminUser.isEmpty() || adminHash.isEmpty()) {
            adminUser = DEFAULT_ADMIN_USER;
            adminHash = hashPassword(DEFAULT_ADMIN_PASSWORD);
            seeded = true;
        }
        // Only a brand new data file gets the starting user; an admin who deleted
        // every user keeps an empty list.
        if (!hasUserList) {
            users.add(newUser(DEFAULT_USER, DEFAULT_USER_PASSWORD));
            seeded = true;
        }
        // Phones linked before accounts existed have no user; they must log in again.
        if (devices.removeIf(d -> str(d, "user").isEmpty())) seeded = true;
        if (seeded) save();
    }

    static void save() {
        Map<String, Object> root = dataRoot();
        try {
            Path tmp = DATA_DIR.resolve("curbox-admin-data.json.tmp");
            Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, DATA, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("Could not save data: " + e);
        }
    }

    /** Call with LOCK held. Everything the data file holds. */
    static Map<String, Object> dataRoot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("adminUser", adminUser);
        root.put("adminHash", adminHash);
        root.put("users", users);
        root.put("devices", devices);
        root.put("removedDevices", removedDevices);
        root.put("policyVersion", policyVersion);
        root.put("releaseCodeHash", releaseCodeHash);
        root.put("releaseCodeSetAt", releaseCodeSetAt);
        root.put("groups", groups);
        root.put("profiles", profiles);
        root.put("approveNewDevices", approveNewDevices);
        root.put("globalSites", globalSites);
        root.put("notifications", notifications);
        root.put("notifyPrefs", notifyPrefs);
        return root;
    }

    // ---------------------------------------------------------------- helpers

    static Map<String, Object> findIn(List<Map<String, Object>> list, String key, String value) {
        for (Map<String, Object> item : list) {
            if (value.equals(item.get(key))) return item;
        }
        return null;
    }

    static Map<String, Object> newUser(String username, String password) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("passwordHash", hashPassword(password));
        user.put("createdAt", System.currentTimeMillis());
        return user;
    }

    static String normalizeUsername(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 3 to 32 characters: letters, digits, dot and underscore. */
    static boolean isValidUsername(String username) {
        return username.matches("[a-z0-9._]{3,32}");
    }

    static void slowDown() {
        try {
            Thread.sleep(700); // slows down password guessing
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static List<String> cleanList(Object raw, boolean sites) {
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o == null || out.size() >= 1000) continue;
                String v = limit(String.valueOf(o).trim(), 200);
                if (sites) v = normalizeSite(v);
                if (!v.isEmpty()) out.add(v);
            }
        }
        return new ArrayList<>(out);
    }

    /** A domain, optionally followed by a path. No ports, spaces, or other schemes. */
    static final Pattern SITE_PATTERN = Pattern.compile(
        "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})"
            + "(?:/[a-z0-9._~%!$&'()*+,;=:@/-]*)?");

    /** The normalized rule, or null when [input] is not a domain or domain/path. */
    static String validSite(String input) {
        String s = normalizeSite(input);
        int cut = s.length();
        for (char c : new char[]{'?', '#'}) {
            int at = s.indexOf(c);
            if (at >= 0) cut = Math.min(cut, at);
        }
        s = s.substring(0, cut);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.length() > 200 || !SITE_PATTERN.matcher(s).matches()) return null;
        return s;
    }

    /** Normalized, without duplicates; the first bad entry stops the request with a 400. */
    static List<String> cleanSites(Object raw) throws BadSiteException {
        Set<String> out = new LinkedHashSet<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o == null) continue;
                String original = String.valueOf(o).trim();
                if (original.isEmpty()) continue;
                String site = validSite(original);
                if (site == null) throw new BadSiteException(original);
                if (out.size() < 1000) out.add(site);
            }
        }
        return new ArrayList<>(out);
    }

    /** "https://www.YouTube.com/watch" becomes "youtube.com/watch"; "www.x.com/" becomes "x.com". */
    static String normalizeSite(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        if (s.startsWith("www.")) s = s.substring(4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The addresses devices use: only the private ones the server listens on. */
    static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        for (InetAddress a : privateAddresses()) out.add(a.getHostAddress() + ":" + PORT);
        return out;
    }

    /** Bundled copy inside the app first (web/ in the jar), then the file next to the source. */
    static InputStream openStatic(String name) throws IOException {
        InputStream bundled = CurboxAdmin.class.getResourceAsStream("/web/" + name);
        if (bundled != null) return bundled;
        Path file = DIR.resolve(name);
        return Files.exists(file) ? Files.newInputStream(file) : null;
    }

    static void serveFile(HttpExchange ex, String name, String type) throws IOException {
        byte[] bytes;
        try (InputStream in = openStatic(name)) {
            if (in == null) {
                sendJson(ex, 404, error("not_found"));
                return;
            }
            bytes = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** No sniffing, no framing, no referrer, and only this server as a source of content. */
    static void securityHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("Content-Security-Policy",
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readJsonObject(HttpExchange ex) throws IOException {
        byte[] bytes;
        try (InputStream in = ex.getRequestBody()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Body too large");
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new LinkedHashMap<>();
        Object parsed = Json.parse(text);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Expected an object");
        return (Map<String, Object>) parsed;
    }

    static String header(HttpExchange ex, String name) {
        String v = ex.getRequestHeaders().getFirst(name);
        return v == null ? "" : v.trim();
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> all = ex.getRequestHeaders().get("Cookie");
        if (all == null) return "";
        for (String line : all) {
            for (String part : line.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return "";
    }

    static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static long num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** Devices are "android" phones or "windows" computers; anything else counts as a phone. */
    static String normalizePlatform(String raw) {
        return "windows".equalsIgnoreCase(raw.trim()) ? "windows" : "android";
    }

    static String limit(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    static Map<String, Object> error(String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", code);
        return m;
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /** Same hashing as the Curbox app (SHA-256, lowercase hex). */
    // ------------------------------------------------------------ passwords

    /** Stored as "pbkdf2$iterations$salt$hash" (base64), with a fresh random salt. */
    static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, PBKDF2_ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    /** True when [password] matches [stored]. Old plain SHA-256 values still match once. */
    static boolean verifyPassword(String password, String stored) {
        if (stored == null || stored.isEmpty()) return false;
        if (!isLegacyHash(stored)) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                return MessageDigest.isEqual(pbkdf2(password, salt, iterations), expected);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return MessageDigest.isEqual(
            sha256(password).getBytes(StandardCharsets.UTF_8),
            stored.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isLegacyHash(String stored) {
        return !stored.startsWith("pbkdf2$");
    }

    // ------------------------------------------------------------ login lockout

    /** Seconds left on a lock for this login, 0 when it is not locked. */
    static long lockedSeconds(String key) {
        long[] entry = loginFailures.get(key);
        if (entry == null) return 0;
        long left = entry[1] - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    /** Five wrong tries in a row lock that login for LOCKOUT_MS. */
    static void recordFailure(String key) {
        recordFailure(key, LOCKOUT_MS);
    }

    static void recordFailure(String key, long lockMs) {
        long now = System.currentTimeMillis();
        loginFailures.compute(key, (k, entry) -> {
            long[] e = entry == null || (entry[1] != 0 && entry[1] <= now) ? new long[]{0, 0} : entry;
            e[0]++;
            if (e[0] >= MAX_LOGIN_FAILURES) {
                e[0] = 0;
                e[1] = now + lockMs;
            }
            return e;
        });
    }

    static void clearFailures(String key) {
        loginFailures.remove(key);
    }

    static Map<String, Object> lockedError(long seconds) {
        Map<String, Object> m = error("locked");
        m.put("retryAfter", seconds);
        return m;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : digest) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ tiny JSON

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            Object v = j.value();
            j.ws();
            if (j.i != text.length()) throw new IllegalArgumentException("Trailing data");
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private Object value() {
            ws();
            if (i >= s.length()) throw new IllegalArgumentException("Unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': word("true"); return Boolean.TRUE;
                case 'f': word("false"); return Boolean.FALSE;
                case 'n': word("null"); return null;
                default: return number();
            }
        }

        private void word(String w) {
            if (!s.startsWith(w, i)) throw new IllegalArgumentException("Bad token");
            i += w.length();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek('}')) {
                i++;
                return m;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                if (!peek(':')) throw new IllegalArgumentException("Expected :");
                i++;
                m.put(key, value());
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                if (peek('}')) {
                    i++;
                    return m;
                }
                throw new IllegalArgumentException("Expected , or }");
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++;
            ws();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(value());
                ws();
                if (peek(',')) {
                    i++;
                    continue;
                }
                if (peek(']')) {
                    i++;
                    return list;
                }
                throw new IllegalArgumentException("Expected , or ]");
            }
        }

        private String string() {
            if (!peek('"')) throw new IllegalArgumentException("Expected string");
            i++;
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new IllegalArgumentException("Bad escape");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("Bad escape");
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("Bad value");
            return Double.parseDouble(s.substring(start, i));
        }

        static String write(Object v) {
            StringBuilder b = new StringBuilder();
            write(v, b);
            return b.toString();
        }

        private static void write(Object v, StringBuilder b) {
            if (v == null) {
                b.append("null");
            } else if (v instanceof String) {
                quote((String) v, b);
            } else if (v instanceof Boolean) {
                b.append(v);
            } else if (v instanceof Number) {
                double d = ((Number) v).doubleValue();
                if (d == Math.rint(d) && Math.abs(d) < 9e15) b.append((long) d);
                else b.append(d);
            } else if (v instanceof Map) {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), b);
                    b.append(':');
                    write(e.getValue(), b);
                }
                b.append('}');
            } else if (v instanceof Collection) {
                b.append('[');
                boolean first = true;
                for (Object o : (Collection<?>) v) {
                    if (!first) b.append(',');
                    first = false;
                    write(o, b);
                }
                b.append(']');
            } else {
                quote(String.valueOf(v), b);
            }
        }

        private static void quote(String s, StringBuilder b) {
            b.append('"');
            for (int k = 0; k < s.length(); k++) {
                char c = s.charAt(k);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                            b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                }
            }
            b.append('"');
        }
    }
}

/**
 * The user side of Curbox for PC: a computer that an admin manages.
 *
 * Setup runs as the signed in Windows user on 127.0.0.1:8789. It asks Admin or User,
 * signs in to the admin's Curbox for PC, and then one Windows permission prompt
 * installs the agent.
 *
 * The agent runs as a Windows task under SYSTEM on 127.0.0.1:8788. It syncs with the
 * admin every 10 seconds, reports the programs in the Start menu, closes blocked
 * programs and blocks websites in its own marked part of the hosts file. Only the
 * admin can remove the link; removing it lifts every block.
 */
final class PcUser {

    static final String MODE_ADMIN = "admin";
    static final String MODE_USER = "user";
    static final String MODE_NONE = "none";
    static final String TASK_NAME = "Curbox for PC Agent";
    static final String APP_VERSION = "1.0.0 (PC)";
    static final int AGENT_PORT = CurboxAdmin.envInt("CURBOX_AGENT_PORT", 8788);
    static final int SETUP_PORT = CurboxAdmin.envInt("CURBOX_SETUP_PORT", 8789);
    static final int DEFAULT_ADMIN_PORT = 8787;
    static final long SYNC_MS = 10_000L;
    static final long ENFORCE_MS = 2_000L;
    static final long PROGRAMS_MS = 10 * 60_000L;
    static final String HOSTS_START = "# >>> Curbox for PC: websites blocked by your admin";
    static final String HOSTS_END = "# <<< Curbox for PC";

    /** Never closed, even when an admin blocks them: Windows needs them, or Curbox does. */
    static final Set<String> ESSENTIAL = Set.of(
        "system", "registry", "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe",
        "lsass.exe", "svchost.exe", "dwm.exe", "explorer.exe", "sihost.exe", "taskhostw.exe", "ctfmon.exe",
        "fontdrvhost.exe", "runtimebroker.exe", "searchhost.exe", "searchapp.exe",
        "startmenuexperiencehost.exe", "shellexperiencehost.exe", "textinputhost.exe", "lockapp.exe",
        "logonui.exe", "conhost.exe", "taskmgr.exe", "curbox for pc.exe", "java.exe", "javaw.exe",
        "powershell.exe", "cmd.exe", "schtasks.exe"
    );

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    // ------------------------------------------------------------ locations

    /** Per Windows user: role, sign in leftovers. Same folder as the admin data. */
    static Path userDir() {
        return CurboxAdmin.DATA_DIR;
    }

    /** Shared by the SYSTEM agent: the link to the admin and its log. */
    static Path agentDir() {
        String override = System.getenv("CURBOX_AGENT_DIR");
        if (override != null && !override.trim().isEmpty()) return Paths.get(override.trim());
        String programData = System.getenv("ProgramData");
        return programData == null ? CurboxAdmin.DATA_DIR : Paths.get(programData, CurboxAdmin.APP_NAME);
    }

    static Path hostsFile() {
        String override = System.getenv("CURBOX_HOSTS_FILE");
        if (override != null && !override.trim().isEmpty()) return Paths.get(override.trim());
        String root = System.getenv("SystemRoot");
        return Paths.get(root == null ? "C:\\Windows" : root, "System32", "drivers", "etc", "hosts");
    }

    static Path modeFile() {
        return userDir().resolve("mode.txt");
    }

    static Path pendingFile() {
        return userDir().resolve("pending-link.json");
    }

    static Path linkFile() {
        return agentDir().resolve("agent-link.json");
    }

    static boolean modeFileExists() {
        return Files.exists(modeFile());
    }

    static String readMode() {
        try {
            return modeFileExists() ? new String(Files.readAllBytes(modeFile()), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    static void writeMode(String mode) {
        try {
            Files.createDirectories(userDir());
            Files.write(modeFile(), mode.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("Could not save the role: " + e);
        }
    }

    static String computerName() {
        String name = System.getenv("COMPUTERNAME");
        return name == null || name.trim().isEmpty() ? "Computer" : name.trim();
    }

    /** Stays the same across sign ins, so the admin keeps this computer's rules. */
    static String deviceId() throws IOException {
        Path file = userDir().resolve("device-id.txt");
        if (Files.exists(file)) {
            String id = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (!id.isEmpty()) return id;
        }
        String id = "pc-" + CurboxAdmin.randomHex(12);
        Files.createDirectories(userDir());
        Files.write(file, id.getBytes(StandardCharsets.UTF_8));
        return id;
    }

    static String lastAddress() {
        try {
            Path file = userDir().resolve("last-address.txt");
            return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    static void writeAtomic(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** "192.168.1.5" or "192.168.1.5:8787" becomes "http://192.168.1.5:8787". "" when unusable. */
    static String normalizeServerUrl(String input) {
        String text = input.trim();
        if (text.isEmpty()) return "";
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://" + text;
        try {
            URI uri = URI.create(text);
            if (uri.getHost() == null) return "";
            int port = uri.getPort() == -1 ? DEFAULT_ADMIN_PORT : uri.getPort();
            return uri.getScheme() + "://" + uri.getHost() + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ http

    static Response httpPost(String url, String json, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null && !token.isEmpty()) c.setRequestProperty("X-Device-Token", token);
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return readResponse(c);
        } finally {
            c.disconnect();
        }
    }

    static Response httpGet(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            return readResponse(c);
        } finally {
            c.disconnect();
        }
    }

    private static Response readResponse(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = "";
        if (in != null) {
            try (InputStream stream = in) {
                body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new Response(code, body);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        try {
            Object parsed = CurboxAdmin.Json.parse(text);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o != null && !String.valueOf(o).trim().isEmpty()) out.add(String.valueOf(o).trim());
            }
        }
        return out;
    }

    /**
     * The local pages only answer this computer's own pages: the Host must be the
     * local address (stops DNS rebinding) and changes need a same origin JSON request
     * (a website cannot send that without the browser asking us first).
     */
    static boolean isLocalRequest(HttpExchange ex, int port) {
        String host = CurboxAdmin.header(ex, "Host");
        if (!host.equals("127.0.0.1:" + port) && !host.equals("localhost:" + port)) return false;
        if (!"POST".equals(ex.getRequestMethod())) return true;
        String origin = CurboxAdmin.header(ex, "Origin");
        if (!origin.isEmpty() && !origin.equals("http://127.0.0.1:" + port) && !origin.equals("http://localhost:" + port)) {
            return false;
        }
        return CurboxAdmin.header(ex, "Content-Type").toLowerCase(Locale.ROOT).contains("application/json");
    }

    static boolean serveCommon(HttpExchange ex, String path) throws IOException {
        switch (path) {
            case "/":
            case "/index.html":
                CurboxAdmin.serveFile(ex, "user.html", "text/html; charset=utf-8");
                return true;
            case "/logo.png":
                CurboxAdmin.serveFile(ex, "logo.png", "image/png");
                return true;
            case "/favicon.ico":
                CurboxAdmin.serveFile(ex, "favicon.ico", "image/x-icon");
                return true;
            default:
                return false;
        }
    }

    static String localUrl(int port) {
        return "http://127.0.0.1:" + port + "/";
    }

    // ------------------------------------------------------------ setup (signed in user)

    /** choose, signin, installing, install_failed, done */
    static volatile String setupStatus = "choose";
    static volatile String setupNote = "";
    static HttpServer setupServer;

    /** Opens the Admin or User choice. [note] instead starts at sign in and says why. */
    static void runSetup(String note) {
        setupStatus = note == null ? "choose" : "signin";
        setupNote = note == null ? "" : note;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), SETUP_PORT), 0);
            server.createContext("/", PcUser::handleSetup);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            setupServer = server;
        } catch (BindException e) {
            System.out.println("Setup is already open.");
        } catch (IOException e) {
            System.out.println("Setup failed: " + e);
            return;
        }
        CurboxAdmin.openWindow(localUrl(SETUP_PORT));
    }

    static void stopSetup() {
        HttpServer server = setupServer;
        setupServer = null;
        if (server != null) server.stop(0);
    }

    static void handleSetup(HttpExchange ex) throws IOException {
        try {
            if (!isLocalRequest(ex, SETUP_PORT)) {
                CurboxAdmin.sendJson(ex, 403, CurboxAdmin.error("forbidden"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if ("GET".equals(ex.getRequestMethod())) {
                if (serveCommon(ex, path)) return;
                if ("/local/state".equals(path)) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("screen", "setup");
                    out.put("status", setupStatus);
                    out.put("note", setupNote);
                    out.put("installed", CurboxAdmin.PACKAGED);
                    out.put("computerName", computerName());
                    out.put("address", lastAddress().replaceFirst("^http://", ""));
                    out.put("agentUrl", localUrl(AGENT_PORT));
                    CurboxAdmin.sendJson(ex, 200, out);
                    return;
                }
            } else if ("POST".equals(ex.getRequestMethod())) {
                switch (path) {
                    case "/local/choose":
                        setupChoose(ex);
                        return;
                    case "/local/signin":
                        setupSignIn(ex);
                        return;
                    case "/local/install":
                        setupInstall(ex);
                        return;
                    default:
                        break;
                }
            }
            CurboxAdmin.sendJson(ex, 404, CurboxAdmin.error("not_found"));
        } catch (IllegalArgumentException e) {
            CurboxAdmin.sendJson(ex, 400, CurboxAdmin.error("bad_request"));
        } catch (Exception e) {
            System.out.println("Setup request failed: " + e);
            CurboxAdmin.sendJson(ex, 500, CurboxAdmin.error("server_error"));
        } finally {
            ex.close();
        }
    }

    static void setupChoose(HttpExchange ex) throws IOException {
        String role = CurboxAdmin.str(CurboxAdmin.readJsonObject(ex), "role");
        if (MODE_ADMIN.equals(role) && Files.exists(linkFile())) {
            // A managed computer leaves management with the admin's code first.
            CurboxAdmin.sendJson(ex, 409, CurboxAdmin.error("managed"));
            return;
        }
        if (MODE_ADMIN.equals(role)) {
            writeMode(MODE_ADMIN);
            Map<String, Object> out = CurboxAdmin.ok();
            // The setup window turns into the admin page; no second window.
            out.put("next", "http://localhost:" + CurboxAdmin.PORT);
            CurboxAdmin.sendJson(ex, 200, out);
            new Thread(() -> {
                try {
                    CurboxAdmin.startAdmin(true, false);
                    Thread.sleep(1500);
                } catch (Exception e) {
                    System.out.println("Could not start admin: " + e);
                }
                stopSetup();
            }).start();
            return;
        }
        if (MODE_USER.equals(role)) {
            setupStatus = "signin";
            CurboxAdmin.sendJson(ex, 200, CurboxAdmin.ok());
            return;
        }
        throw new IllegalArgumentException("Unknown role");
    }

    static void setupSignIn(HttpExchange ex) throws IOException {
        Map<String, Object> body = CurboxAdmin.readJsonObject(ex);
        String url = normalizeServerUrl(CurboxAdmin.str(body, "address"));
        String username = CurboxAdmin.normalizeUsername(CurboxAdmin.str(body, "username"));
        String password = CurboxAdmin.str(body, "password");
        if (url.isEmpty()) {
            CurboxAdmin.sendJson(ex, 400, CurboxAdmin.error("bad_address"));
            return;
        }
        if (username.isEmpty() || password.isEmpty()) {
            CurboxAdmin.sendJson(ex, 400, CurboxAdmin.error("missing"));
            return;
        }

        String deviceId = deviceId();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("username", username);
        request.put("password", password);
        request.put("deviceId", deviceId);
        request.put("deviceName", computerName());
        request.put("platform", "windows");
        request.put("osVersion", CurboxAdmin.limit(System.getProperty("os.name", "Windows"), 40));

        Response response;
        try {
            response = httpPost(url + "/api/device/login", CurboxAdmin.Json.write(request), null);
        } catch (IOException e) {
            CurboxAdmin.sendJson(ex, 502, CurboxAdmin.error("unreachable"));
            return;
        }
        if (response.code == 403) {
            CurboxAdmin.sendJson(ex, 403, CurboxAdmin.error("wrong_login"));
            return;
        }
        if (response.code == 429) {
            CurboxAdmin.sendJson(ex, 429, CurboxAdmin.error("locked"));
            return;
        }
        Map<String, Object> parsed = response.code == 200 ? parseObject(response.body) : null;
        String token = parsed == null ? "" : CurboxAdmin.str(parsed, "token");
        if (token.isEmpty()) {
            CurboxAdmin.sendJson(ex, 502, CurboxAdmin.error("unreachable"));
            return;
        }

        Map<String, Object> link = new LinkedHashMap<>();
        link.put("serverUrl", url);
        link.put("deviceId", deviceId);
        link.put("deviceToken", token);
        link.put("deviceName", computerName());
        link.put("username", username);
        link.put("blockedApps", new ArrayList<>());
        link.put("blockedSites", new ArrayList<>());
        Files.createDirectories(userDir());
        writeAtomic(pendingFile(), CurboxAdmin.Json.write(link));
        Files.write(userDir().resolve("last-address.txt"), url.getBytes(StandardCharsets.UTF_8));

        setupStatus = "install";
        CurboxAdmin.sendJson(ex, 200, CurboxAdmin.ok());
    }

    static void setupInstall(HttpExchange ex) throws IOException {
        if (!CurboxAdmin.PACKAGED) {
            CurboxAdmin.sendJson(ex, 409, CurboxAdmin.error("not_installed"));
            return;
        }
        if (!Files.exists(pendingFile())) {
            CurboxAdmin.sendJson(ex, 409, CurboxAdmin.error("no_signin"));
            return;
        }
        setupStatus = "installing";
        CurboxAdmin.sendJson(ex, 200, CurboxAdmin.ok());

        new Thread(() -> {
            int code = runElevated(CurboxAdmin.APP_PATH, "--install-agent", pendingFile().toString());
            if (code != 0) {
                System.out.println("Agent install ended with " + code);
                setupStatus = "install_failed";
                return;
            }
            writeMode(MODE_USER);
            startTracker();
            try {
                Files.deleteIfExists(pendingFile());
            } catch (IOException ignored) {
            }
            setupStatus = "done";
            try {
                Thread.sleep(5000); // lets the page read "done" and move to the agent page
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stopSetup();
            System.exit(0);
        }).start();
    }

    /** Runs this app again with admin rights. Windows shows its permission prompt; a "No" returns non zero. */
    static int runElevated(String exe, String flag, String argument) {
        String script = "$p = Start-Process -FilePath '" + exe.replace("'", "''") + "'"
            + " -ArgumentList '" + flag + "', '\"" + argument.replace("'", "''") + "\"'"
            + " -Verb RunAs -Wait -PassThru; exit $p.ExitCode";
        return CurboxAdmin.runQuietly("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
    }

    /**
     * Elevated: saves the link for the agent and registers it as a Windows task that
     * runs as SYSTEM at start up, then starts it. Returns 0 on success.
     */
    static int installAgent(String pendingPath) {
        try {
            if (CurboxAdmin.APP_PATH == null) return 2;
            Map<String, Object> link = parseObject(new String(Files.readAllBytes(Paths.get(pendingPath)), StandardCharsets.UTF_8));
            if (link == null || CurboxAdmin.str(link, "deviceToken").isEmpty()) return 3;

            Files.createDirectories(agentDir());
            writeAtomic(linkFile(), CurboxAdmin.Json.write(link));

            // Task Scheduler reads UTF-16 little endian with a byte order mark.
            Path xml = agentDir().resolve("agent-task.xml");
            byte[] body = taskXml(CurboxAdmin.APP_PATH).getBytes(StandardCharsets.UTF_16LE);
            byte[] withBom = new byte[body.length + 2];
            withBom[0] = (byte) 0xFF;
            withBom[1] = (byte) 0xFE;
            System.arraycopy(body, 0, withBom, 2, body.length);
            Files.write(xml, withBom);
            // Restart a running agent so it reads the new link.
            CurboxAdmin.runQuietly("schtasks", "/End", "/TN", TASK_NAME);
            if (CurboxAdmin.runQuietly("schtasks", "/Create", "/TN", TASK_NAME, "/XML", xml.toString(), "/F") != 0) return 4;
            return CurboxAdmin.runQuietly("schtasks", "/Run", "/TN", TASK_NAME) == 0 ? 0 : 5;
        } catch (Exception e) {
            System.out.println("Agent install failed: " + e);
            return 1;
        }
    }

    static String taskXml(String exe) {
        String command = exe.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n"
            + "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\r\n"
            + "  <RegistrationInfo><Description>Curbox for PC: applies the programs and websites blocked by the admin.</Description></RegistrationInfo>\r\n"
            + "  <Triggers><BootTrigger><Enabled>true</Enabled></BootTrigger></Triggers>\r\n"
            + "  <Principals><Principal id=\"Author\"><UserId>S-1-5-18</UserId><RunLevel>HighestAvailable</RunLevel></Principal></Principals>\r\n"
            + "  <Settings>\r\n"
            + "    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n"
            + "    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\r\n"
            + "    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\r\n"
            + "    <AllowHardTerminate>true</AllowHardTerminate>\r\n"
            + "    <StartWhenAvailable>true</StartWhenAvailable>\r\n"
            + "    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>\r\n"
            + "    <IdleSettings><StopOnIdleEnd>false</StopOnIdleEnd><RestartOnIdle>false</RestartOnIdle></IdleSettings>\r\n"
            + "    <AllowStartOnDemand>true</AllowStartOnDemand>\r\n"
            + "    <Enabled>true</Enabled>\r\n"
            + "    <Hidden>false</Hidden>\r\n"
            + "    <RunOnlyIfIdle>false</RunOnlyIfIdle>\r\n"
            + "    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\r\n"
            + "    <Priority>7</Priority>\r\n"
            + "    <RestartOnFailure><Interval>PT1M</Interval><Count>999</Count></RestartOnFailure>\r\n"
            + "  </Settings>\r\n"
            + "  <Actions Context=\"Author\"><Exec><Command>" + command + "</Command><Arguments>--agent</Arguments></Exec></Actions>\r\n"
            + "</Task>\r\n";
    }

    // ------------------------------------------------------------ usage helper (A3)

    static final long TRACK_SAMPLE_MS = 5_000L;
    static final long TRACK_IDLE_MS = 120_000L;
    static final long USAGE_SEND_MS = 5 * 60_000L;
    static final String RUN_KEY = "HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String RUN_VALUE = "Curbox for PC usage";
    /** The lock screen and sign in screen are in front while nobody uses the PC. */
    static final Set<String> TRACK_SKIP = Set.of("lockapp.exe", "logonui.exe", "idle.exe");
    static volatile long lastUsageMs = 0L;
    static volatile int failedSyncs = 0;
    static volatile boolean hostsOk = true;

    /**
     * Prints "pid|idle ms|process name" every 5 seconds: the program in front and how long
     * since the last key press or mouse move. Plain Windows calls, no extra download.
     */
    static final String TRACKER_SCRIPT = String.join("\n",
        "$ErrorActionPreference = 'Stop'", // a closed pipe (helper gone) ends this loop
        "[Console]::OutputEncoding = [Text.Encoding]::UTF8",
        "Add-Type -TypeDefinition @'",
        "using System;",
        "using System.Runtime.InteropServices;",
        "public static class CurboxFront {",
        "  [StructLayout(LayoutKind.Sequential)] public struct LastInput { public uint Size; public uint Time; }",
        "  [DllImport(\"user32.dll\")] static extern IntPtr GetForegroundWindow();",
        "  [DllImport(\"user32.dll\")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);",
        "  [DllImport(\"user32.dll\")] static extern bool GetLastInputInfo(ref LastInput info);",
        "  public static uint Pid() { uint pid = 0; IntPtr w = GetForegroundWindow(); if (w != IntPtr.Zero) GetWindowThreadProcessId(w, out pid); return pid; }",
        "  public static uint IdleMs() { LastInput i = new LastInput(); i.Size = (uint)Marshal.SizeOf(i); if (!GetLastInputInfo(ref i)) return 0; return unchecked((uint)Environment.TickCount - i.Time); }",
        "}",
        "'@",
        "while ($true) {",
        "  $id = [CurboxFront]::Pid(); $name = ''",
        "  if ($id -gt 0) { try { $name = [Diagnostics.Process]::GetProcessById([int]$id).ProcessName } catch { } }",
        "  [Console]::Out.WriteLine(\"$id|$([CurboxFront]::IdleMs())|$name\"); [Console]::Out.Flush()",
        "  Start-Sleep -Seconds 5",
        "}");

    /** Shared by every Windows user on this PC; the agent reads it. */
    static Path usageDir() {
        return agentDir().resolve("usage");
    }

    /**
     * The usage helper. Windows starts it at sign in for each user, because the SYSTEM agent
     * cannot see which window is in front. Counts 5 seconds for the program in front,
     * skips time with no keyboard or mouse use for 2 minutes, and saves once a minute.
     */
    static void runTracker() throws IOException, InterruptedException {
        Files.createDirectories(CurboxAdmin.DATA_DIR);
        java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
            CurboxAdmin.DATA_DIR.resolve("usage-helper.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        if (channel.tryLock() == null) {
            System.out.println("The usage helper is already running for this user.");
            channel.close();
            return;
        }
        if (CurboxAdmin.PACKAGED) CurboxAdmin.redirectLogs(CurboxAdmin.DATA_DIR, "curbox-usage.log");
        String user = System.getProperty("user.name", "user").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        Path out = usageDir().resolve(user + ".json");
        Map<String, Map<String, Long>> days = readUsageFile(out);
        System.out.println("Usage helper running. Saving to " + out);
        String encoded = Base64.getEncoder().encodeToString(TRACKER_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        long lastSave = System.currentTimeMillis();
        while (true) {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start();
            Thread stopReader = new Thread(p::destroyForcibly);
            Runtime.getRuntime().addShutdownHook(stopReader);
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\|", -1);
                    if (parts.length != 3) continue;
                    long idle;
                    try {
                        idle = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    String exe = parts[2].trim().toLowerCase(Locale.ROOT);
                    if (!exe.isEmpty() && idle < TRACK_IDLE_MS) {
                        exe = exe + ".exe";
                        if (!TRACK_SKIP.contains(exe)) {
                            days.computeIfAbsent(LocalDate.now().toString(), k -> new java.util.TreeMap<>())
                                .merge(exe, TRACK_SAMPLE_MS, Long::sum);
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastSave >= 60_000L) {
                        lastSave = now;
                        saveUsageFile(out, user, days);
                    }
                }
            }
            Runtime.getRuntime().removeShutdownHook(stopReader);
            saveUsageFile(out, user, days);
            System.out.println("The front window reader stopped; starting it again in 30 seconds.");
            Thread.sleep(30_000L);
        }
    }

    static Map<String, Map<String, Long>> readUsageFile(Path file) {
        Map<String, Map<String, Long>> days = new java.util.TreeMap<>();
        Map<String, Object> root = null;
        try {
            if (Files.exists(file)) root = parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.out.println("Could not read " + file + ": " + e);
        }
        Object raw = root == null ? null : root.get("days");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> day : ((Map<?, ?>) raw).entrySet()) {
                if (!(day.getValue() instanceof Map)) continue;
                Map<String, Long> apps = new java.util.TreeMap<>();
                for (Map.Entry<?, ?> app : ((Map<?, ?>) day.getValue()).entrySet()) {
                    if (app.getValue() instanceof Number) apps.put(String.valueOf(app.getKey()), ((Number) app.getValue()).longValue());
                }
                days.put(String.valueOf(day.getKey()), apps);
            }
        }
        return days;
    }

    /** Keeps three days; the agent only sends today and yesterday. */
    static void saveUsageFile(Path file, String user, Map<String, Map<String, Long>> days) {
        String oldest = LocalDate.now().minusDays(2).toString();
        days.keySet().removeIf(date -> date.compareTo(oldest) < 0);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("user", user);
        root.put("days", days);
        try {
            Files.createDirectories(file.getParent());
            writeAtomic(file, CurboxAdmin.Json.write(root));
        } catch (IOException e) {
            System.out.println("Could not save usage: " + e);
        }
    }

    /**
     * Today and yesterday for the sync body, summed over every Windows user's file, or
     * null when no helper has written anything (the panel then says usage is not available).
     */
    static List<Object> collectUsage() {
        Path dir = usageDir();
        if (!Files.isDirectory(dir)) return null;
        LocalDate today = LocalDate.now();
        Map<String, Map<String, Long>> sums = new LinkedHashMap<>();
        boolean any = false;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json"))::iterator) {
                any = true;
                Map<String, Map<String, Long>> days = readUsageFile(file);
                for (int ago = 0; ago <= 1; ago++) {
                    String date = today.minusDays(ago).toString();
                    Map<String, Long> apps = days.get(date);
                    if (apps == null) continue;
                    Map<String, Long> sum = sums.computeIfAbsent(date, k -> new LinkedHashMap<>());
                    apps.forEach((exe, ms) -> sum.merge(exe, ms, Long::sum));
                }
            }
        } catch (IOException e) {
            System.out.println("Could not read usage: " + e);
            return null;
        }
        if (!any) return null;
        List<Object> out = new ArrayList<>();
        for (int ago = 0; ago <= 1; ago++) {
            String date = today.minusDays(ago).toString();
            // A day the helper did not run is unknown, not zero, so it is not sent.
            Map<String, Long> apps = sums.get(date);
            if (apps == null) continue;
            List<Object> list = new ArrayList<>();
            long total = 0;
            for (Map.Entry<String, Long> e : apps.entrySet()) {
                Map<String, Object> app = new LinkedHashMap<>();
                app.put("p", e.getKey());
                app.put("ms", e.getValue());
                list.add(app);
                total += e.getValue();
            }
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date);
            // Only the program in front is counted, so the programs never overlap.
            day.put("totalMs", total);
            day.put("apps", list);
            out.add(day);
        }
        return out;
    }

    /**
     * Run by the agent (SYSTEM) at start: makes the shared usage folder writable for
     * Windows users and starts the helper at every sign in. Only for the installed app.
     */
    static void setUpUsageHelper() {
        if (!CurboxAdmin.PACKAGED) return;
        try {
            Files.createDirectories(usageDir());
        } catch (IOException e) {
            System.out.println("Could not create " + usageDir() + ": " + e);
            return;
        }
        // S-1-5-32-545 is the local Users group, whatever the Windows language.
        CurboxAdmin.runQuietly("icacls", usageDir().toString(), "/grant", "*S-1-5-32-545:(OI)(CI)M");
        String value = "\"" + CurboxAdmin.APP_PATH + "\" --tracker";
        String script = "New-ItemProperty -Path '" + RUN_KEY + "' -Name '" + RUN_VALUE + "' -Value '"
            + value.replace("'", "''") + "' -PropertyType String -Force | Out-Null";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        CurboxAdmin.runQuietly("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
    }

    /** Starts the helper in this user's session now, instead of at the next sign in. */
    static void startTracker() {
        if (!CurboxAdmin.PACKAGED) return;
        try {
            new ProcessBuilder(CurboxAdmin.APP_PATH, "--tracker").start();
        } catch (IOException e) {
            System.out.println("Could not start the usage helper: " + e);
        }
    }

    /** Desktop shortcut on a managed PC: show the agent's status, or sign in again if needed. */
    static void openUserWindow() {
        startTracker();
        Map<String, Object> state = agentState();
        if (state == null) {
            CurboxAdmin.runQuietly("schtasks", "/Run", "/TN", TASK_NAME);
            for (int i = 0; i < 10 && state == null; i++) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                state = agentState();
            }
        }
        if (state == null) {
            runSetup("Protection is not running on this computer. Sign in again to turn it back on.");
            return;
        }
        if (!Boolean.TRUE.equals(state.get("linked"))) {
            runSetup("This computer is not linked to an admin. Sign in to link it.");
            return;
        }
        CurboxAdmin.openWindow(localUrl(AGENT_PORT));
    }

    /**
     * "Leave management" on the status page: the admin's computer checks the six digit
     * code. Only its yes lifts the blocks; offline, nothing changes.
     */
    static void agentRelease(HttpExchange ex) throws IOException {
        String code = CurboxAdmin.str(CurboxAdmin.readJsonObject(ex), "code").trim();
        if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException();
        Map<String, Object> current;
        synchronized (AGENT_LOCK) {
            current = link;
        }
        if (current == null) {
            CurboxAdmin.sendJson(ex, 409, CurboxAdmin.error("not_linked"));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        Response r;
        try {
            r = httpPost(CurboxAdmin.str(current, "serverUrl") + "/api/device/release",
                CurboxAdmin.Json.write(body), CurboxAdmin.str(current, "deviceToken"));
        } catch (IOException e) {
            CurboxAdmin.sendJson(ex, 503, CurboxAdmin.error("offline"));
            return;
        }
        if (r.code == 200 || r.code == 401) {
            synchronized (AGENT_LOCK) {
                link = null;
            }
            Files.deleteIfExists(linkFile());
            applyRules();
            syncState = "removed";
            System.out.println("Released with the admin's code; blocks lifted.");
            CurboxAdmin.sendJson(ex, 200, CurboxAdmin.ok());
            return;
        }
        // Wrong code, lock or no code set: pass the server's answer on.
        Map<String, Object> answer = parseObject(r.body);
        CurboxAdmin.sendJson(ex, r.code, answer == null ? CurboxAdmin.error("failed") : answer);
    }

    static Map<String, Object> agentState() {
        try {
            Response r = httpGet(localUrl(AGENT_PORT) + "local/state", 2000);
            return r.code == 200 ? parseObject(r.body) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ agent (SYSTEM)

    static final Object AGENT_LOCK = new Object();
    static Map<String, Object> link;
    /** starting, connected, offline, removed, not_linked */
    static volatile String syncState = "starting";
    static volatile long lastSyncMs = 0L;
    static volatile long lastProgramsMs = 0L;
    static volatile List<Map<String, Object>> programs = new ArrayList<>();
    static volatile Set<String> blockedExes = new LinkedHashSet<>();
    static final Map<String, Long> lastClosed = new ConcurrentHashMap<>();

    static void runAgent() throws IOException {
        Files.createDirectories(agentDir());
        if (CurboxAdmin.PACKAGED) CurboxAdmin.redirectLogs(agentDir(), "curbox-agent.log");
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), AGENT_PORT), 0);
        } catch (BindException e) {
            System.out.println("The agent is already running.");
            return;
        }
        server.createContext("/", PcUser::handleAgent);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        synchronized (AGENT_LOCK) {
            link = readLink();
        }
        syncState = link == null ? "not_linked" : "starting";
        safely("apply rules", PcUser::applyRules);
        safely("usage helper setup", PcUser::setUpUsageHelper);

        ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
        // A thrown error would silently stop a scheduled task, so each run is guarded.
        timer.scheduleWithFixedDelay(() -> safely("sync", PcUser::sync), 0, SYNC_MS, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(() -> safely("enforce", PcUser::enforce), 1000, ENFORCE_MS, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(() -> safely("schedules", PcUser::applyRules), 30, 30, TimeUnit.SECONDS);
        System.out.println("Agent running. Linked: " + (link != null) + ". Hosts: " + hostsFile());
    }

    interface Step {
        void run() throws Exception;
    }

    static void safely(String name, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            System.out.println("Agent " + name + " failed: " + t);
        }
    }

    static Map<String, Object> readLink() {
        try {
            return Files.exists(linkFile())
                ? parseObject(new String(Files.readAllBytes(linkFile()), StandardCharsets.UTF_8))
                : null;
        } catch (IOException e) {
            return null;
        }
    }

    static void sync() throws IOException {
        Map<String, Object> current;
        synchronized (AGENT_LOCK) {
            current = link;
        }
        if (current == null) {
            if (!"removed".equals(syncState)) syncState = "not_linked";
            return;
        }

        long now = System.currentTimeMillis();
        boolean sendPrograms = now - lastProgramsMs >= PROGRAMS_MS;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceName", computerName());
        body.put("appVersion", APP_VERSION);
        body.put("platform", "windows");
        body.put("osVersion", CurboxAdmin.limit(System.getProperty("os.name", "Windows"), 40));
        // Tells the admin which rules this computer has.
        body.put("policyVersion", CurboxAdmin.num(current, "policyVersion"));
        if (sendPrograms) {
            programs = scanPrograms();
            body.put("apps", programs);
        }
        if (sendPrograms) {
            Map<String, Object> protection = new LinkedHashMap<>();
            protection.put("agent", true);
            protection.put("usageHelper", collectUsage() != null);
            protection.put("hostsFile", hostsOk);
            body.put("protection", protection);
        }
        if (failedSyncs > 0) body.put("failedSyncs", failedSyncs);
        boolean sendUsage = now - lastUsageMs >= USAGE_SEND_MS;
        if (sendUsage) {
            List<Object> usage = collectUsage();
            if (usage != null) body.put("usage", usage);
        }

        Response r;
        try {
            r = httpPost(CurboxAdmin.str(current, "serverUrl") + "/api/device/sync",
                CurboxAdmin.Json.write(body), CurboxAdmin.str(current, "deviceToken"));
        } catch (IOException e) {
            // The admin computer is off or out of reach. The last rules keep working.
            syncState = "offline";
            failedSyncs++;
            return;
        }
        if (r.code == 401) {
            // The admin removed this computer, which lifts its blocks.
            synchronized (AGENT_LOCK) {
                link = null;
            }
            Files.deleteIfExists(linkFile());
            applyRules();
            syncState = "removed";
            System.out.println("Removed by the admin; blocks lifted.");
            return;
        }
        Map<String, Object> parsed = r.code == 200 ? parseObject(r.body) : null;
        if (parsed == null) {
            syncState = "offline";
            return;
        }
        if (sendPrograms) lastProgramsMs = now;
        failedSyncs = 0;
        // Not approved yet: the server sends no rules, and the page says why.
        boolean pending = Boolean.TRUE.equals(parsed.get("pending"));
        if (sendUsage) lastUsageMs = now;

        List<String> apps = stringList(parsed.get("blockedApps"));
        List<String> sites = stringList(parsed.get("blockedSites"));
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("blockedApps", apps);
        rules.put("blockedSites", sites);
        rules.put("dailyLimitMinutes", CurboxAdmin.num(parsed, "dailyLimitMinutes"));
        rules.put("schedules", parsed.get("schedules") instanceof List ? parsed.get("schedules") : new ArrayList<>());
        rules.put("policyVersion", CurboxAdmin.num(parsed, "policyVersion"));
        boolean changed = false;
        synchronized (AGENT_LOCK) {
            if (link == null) return;
            for (Map.Entry<String, Object> e : rules.entrySet()) {
                if (!CurboxAdmin.sameJson(e.getValue(), link.get(e.getKey()))) {
                    link.put(e.getKey(), e.getValue());
                    changed = true;
                }
            }
            if (changed) writeAtomic(linkFile(), CurboxAdmin.Json.write(link));
        }
        if (changed) {
            applyRules();
            System.out.println("New rules: " + apps.size() + " programs, " + sites.size() + " websites, "
                + ((List<?>) rules.get("schedules")).size() + " schedules, limit " + rules.get("dailyLimitMinutes") + " min.");
        }
        lastSyncMs = now;
        syncState = pending ? "pending" : "connected";
    }

    /** Sites last written to the hosts file; null until the first write after start. */
    static List<String> hostsSites = null;
    static volatile String limitState = "none";
    static volatile List<String> activeScheduleNames = new ArrayList<>();

    /**
     * Base blocks plus the schedules active right now; at the daily limit, or in a
     * "block all" schedule, every program from the Start menu too. Runs after each rule
     * change and every 30 seconds, so schedules start and end on time.
     */
    static synchronized void applyRules() throws IOException {
        List<String> apps;
        List<String> sites;
        long limit;
        List<?> schedules;
        synchronized (AGENT_LOCK) {
            apps = link == null ? new ArrayList<>() : stringList(link.get("blockedApps"));
            sites = link == null ? new ArrayList<>() : stringList(link.get("blockedSites"));
            limit = link == null ? 0 : CurboxAdmin.num(link, "dailyLimitMinutes");
            schedules = link != null && link.get("schedules") instanceof List ? (List<?>) link.get("schedules") : new ArrayList<>();
        }
        Set<String> appSet = new LinkedHashSet<>(apps);
        Set<String> siteSet = new LinkedHashSet<>(sites);
        boolean blockAll = false;
        List<String> active = new ArrayList<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (Object o : schedules) {
            if (!(o instanceof Map) || !scheduleActive((Map<?, ?>) o, now)) continue;
            Map<?, ?> schedule = (Map<?, ?>) o;
            active.add(String.valueOf(schedule.get("name")));
            appSet.addAll(stringList(schedule.get("blockApps")));
            siteSet.addAll(stringList(schedule.get("blockSites")));
            if (Boolean.TRUE.equals(schedule.get("blockAllApps"))) blockAll = true;
        }
        activeScheduleNames = active;
        if (limit > 0) {
            Long used = usedTodayMs();
            if (used == null) {
                // Without the usage helper there is nothing to measure, so the limit cannot apply.
                limitState = "no_usage";
            } else if (used >= limit * 60_000L) {
                limitState = "reached";
                blockAll = true;
            } else {
                limitState = "ok";
            }
        } else {
            limitState = "none";
        }
        if (blockAll) {
            for (Map<String, Object> program : programs) appSet.add(String.valueOf(program.get("p")));
        }

        Set<String> exes = new LinkedHashSet<>();
        for (String app : appSet) {
            String exe = app.toLowerCase(Locale.ROOT);
            exe = exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1);
            if (!exe.endsWith(".exe")) exe = exe + ".exe";
            if (!ESSENTIAL.contains(exe)) exes.add(exe);
        }
        blockedExes = exes;
        List<String> siteList = new ArrayList<>(siteSet);
        if (!siteList.equals(hostsSites)) {
            try {
                writeHosts(siteList);
                hostsOk = true;
            } catch (IOException e) {
                hostsOk = false;
                throw e;
            }
            hostsSites = siteList;
        }
    }

    /** Days 1 (Monday) to 7; an end before the start means the schedule runs past midnight. */
    static boolean scheduleActive(Map<?, ?> schedule, java.time.LocalDateTime at) {
        if (Boolean.FALSE.equals(schedule.get("enabled"))) return false;
        Set<Integer> days = new LinkedHashSet<>();
        if (schedule.get("days") instanceof List) {
            for (Object d : (List<?>) schedule.get("days")) if (d instanceof Number) days.add(((Number) d).intValue());
        }
        int start = minuteOfDay(String.valueOf(schedule.get("start")));
        int end = minuteOfDay(String.valueOf(schedule.get("end")));
        if (start < 0 || end < 0 || start == end) return false;
        int day = at.getDayOfWeek().getValue();
        int previous = day == 1 ? 7 : day - 1;
        int minute = at.getHour() * 60 + at.getMinute();
        if (start < end) return days.contains(day) && minute >= start && minute < end;
        return (days.contains(day) && minute >= start) || (days.contains(previous) && minute < end);
    }

    static int minuteOfDay(String hhmm) {
        if (!CurboxAdmin.HHMM.matcher(hhmm).matches()) return -1;
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
    }

    /** Use measured on this computer today, or null when no usage helper has written anything. */
    static Long usedTodayMs() {
        List<Object> usage = collectUsage();
        if (usage == null) return null;
        String today = LocalDate.now().toString();
        for (Object o : usage) {
            Map<?, ?> day = (Map<?, ?>) o;
            if (today.equals(day.get("date")) && day.get("totalMs") instanceof Number) return ((Number) day.get("totalMs")).longValue();
        }
        return 0L;
    }

    /** Closes running blocked programs. Runs every two seconds. */
    static void enforce() {
        Set<String> blocked = blockedExes;
        if (blocked.isEmpty()) return;
        long self = ProcessHandle.current().pid();
        long now = System.currentTimeMillis();
        ProcessHandle.allProcesses().forEach(process -> {
            try {
                if (process.pid() == self) return;
                Optional<String> command = process.info().command();
                if (!command.isPresent()) return;
                String exe = Paths.get(command.get()).getFileName().toString().toLowerCase(Locale.ROOT);
                if (!blocked.contains(exe) || ESSENTIAL.contains(exe)) return;
                if (process.destroyForcibly()) {
                    Long previous = lastClosed.put(exe, now);
                    if (previous == null || now - previous > 30_000L) System.out.println("Closed blocked program " + exe);
                }
            } catch (Exception ignored) {
                // Some system processes cannot be read; they are never blocked anyway.
            }
        });
    }

    /**
     * Rewrites only the marked Curbox part of the hosts file. A name goes in only if it
     * looks like a plain domain, so admin text can never add other lines to the file.
     */
    static void writeHosts(List<String> sites) throws IOException {
        Path hosts = hostsFile();
        String original = Files.exists(hosts) ? new String(Files.readAllBytes(hosts), StandardCharsets.ISO_8859_1) : "";
        String kept = removeHostsSection(original).replaceAll("[\\r\\n]+$", "");

        Set<String> names = new LinkedHashSet<>();
        for (String site : sites) {
            String domain = site.trim().toLowerCase(Locale.ROOT);
            // Paths like youtube.com/shorts cannot be blocked for a whole computer.
            if (!domain.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) continue;
            String bare = domain.startsWith("www.") ? domain.substring(4) : domain;
            names.add(bare);
            names.add("www." + bare);
            names.add("m." + bare);
        }

        StringBuilder sb = new StringBuilder(kept);
        if (!names.isEmpty()) {
            sb.append("\r\n\r\n").append(HOSTS_START).append("\r\n");
            for (String name : names) sb.append("0.0.0.0 ").append(name).append("\r\n");
            sb.append(HOSTS_END);
        }
        sb.append("\r\n");
        String updated = sb.toString();
        if (updated.equals(original)) return;

        Files.write(hosts, updated.getBytes(StandardCharsets.ISO_8859_1));
        CurboxAdmin.runQuietly("ipconfig", "/flushdns");
        System.out.println("Hosts file updated: " + names.size() + " names blocked.");
    }

    static String removeHostsSection(String text) {
        int start = text.indexOf(HOSTS_START);
        if (start < 0) return text;
        int end = text.indexOf(HOSTS_END, start);
        String after = end < 0 ? "" : text.substring(end + HOSTS_END.length());
        return text.substring(0, start) + after.replaceFirst("^\\r?\\n", "");
    }

    /** Programs in every user's Start menu, as [{"p": "telegram.exe", "l": "Telegram"}]. */
    static List<Map<String, Object>> scanPrograms() {
        String script = String.join("\n",
            "[Console]::OutputEncoding = [Text.Encoding]::UTF8",
            "$sh = New-Object -ComObject WScript.Shell",
            "$roots = @(Join-Path $env:ProgramData 'Microsoft\\Windows\\Start Menu\\Programs')",
            "Get-ChildItem (Join-Path $env:SystemDrive 'Users') -Directory -ErrorAction SilentlyContinue | ForEach-Object {",
            "  $roots += Join-Path $_.FullName 'AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs' }",
            "Get-ChildItem -Path $roots -Recurse -Filter *.lnk -ErrorAction SilentlyContinue | ForEach-Object {",
            "  try { $t = $sh.CreateShortcut($_.FullName).TargetPath } catch { $t = '' }",
            "  if ($t -and $t.ToLower().EndsWith('.exe')) { $_.BaseName + '|' + [IO.Path]::GetFileName($t) } }");
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Path out = agentDir().resolve("programs.txt");
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start();
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return programs;
            }
            Map<String, String> byExe = new LinkedHashMap<>();
            for (String line : Files.readAllLines(out, StandardCharsets.UTF_8)) {
                int bar = line.lastIndexOf('|');
                if (bar <= 0) continue;
                String name = line.substring(0, bar).replace("\uFEFF", "").trim();
                String exe = line.substring(bar + 1).trim().toLowerCase(Locale.ROOT);
                if (name.toLowerCase(Locale.ROOT).contains("uninstall") || exe.startsWith("unins")) continue;
                if (exe.isEmpty() || ESSENTIAL.contains(exe)) continue;
                byExe.putIfAbsent(exe, name);
            }
            List<Map<String, Object>> list = new ArrayList<>();
            byExe.forEach((exe, name) -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("p", exe);
                entry.put("l", name);
                list.add(entry);
            });
            list.sort((a, b) -> String.valueOf(a.get("l")).compareToIgnoreCase(String.valueOf(b.get("l"))));
            return list;
        } catch (Exception e) {
            System.out.println("Program scan failed: " + e);
            return programs;
        }
    }

    static void handleAgent(HttpExchange ex) throws IOException {
        try {
            if (!isLocalRequest(ex, AGENT_PORT)) {
                CurboxAdmin.sendJson(ex, 403, CurboxAdmin.error("forbidden"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if ("POST".equals(ex.getRequestMethod())) {
                if ("/local/release".equals(path)) {
                    agentRelease(ex);
                } else {
                    CurboxAdmin.sendJson(ex, 404, CurboxAdmin.error("not_found"));
                }
                return;
            }
            if (!"GET".equals(ex.getRequestMethod())) {
                CurboxAdmin.sendJson(ex, 405, CurboxAdmin.error("method_not_allowed"));
                return;
            }
            if (serveCommon(ex, path)) return;
            if (!"/local/state".equals(path)) {
                CurboxAdmin.sendJson(ex, 404, CurboxAdmin.error("not_found"));
                return;
            }
            Map<String, Object> current;
            synchronized (AGENT_LOCK) {
                current = link == null ? null : new LinkedHashMap<>(link);
            }
            Map<String, String> labels = new LinkedHashMap<>();
            for (Map<String, Object> program : programs) {
                labels.put(String.valueOf(program.get("p")), String.valueOf(program.get("l")));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("screen", "agent");
            out.put("linked", current != null);
            out.put("state", syncState);
            out.put("lastSyncMs", lastSyncMs);
            out.put("computerName", computerName());
            if (current != null) {
                out.put("username", CurboxAdmin.str(current, "username"));
                out.put("server", CurboxAdmin.str(current, "serverUrl").replaceFirst("^http://", ""));
                List<Object> blockedPrograms = new ArrayList<>();
                for (String exe : blockedExes) blockedPrograms.add(labels.getOrDefault(exe, exe));
                out.put("blockedPrograms", blockedPrograms);
                out.put("blockedSites", stringList(current.get("blockedSites")));
            }
            CurboxAdmin.sendJson(ex, 200, out);
        } catch (IllegalArgumentException e) {
            CurboxAdmin.sendJson(ex, 400, CurboxAdmin.error("bad_request"));
        } catch (Exception e) {
            System.out.println("Agent page failed: " + e);
            CurboxAdmin.sendJson(ex, 500, CurboxAdmin.error("server_error"));
        } finally {
            ex.close();
        }
    }
}

/**
 * A small QR code encoder: byte mode, error correction level M, versions 1 to 10
 * (up to 213 bytes, plenty for a link). Follows ISO/IEC 18004; no library needed.
 */
final class Qr {

    // Error correction codewords per block and number of blocks, level M, versions 1 to 10.
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_LEVEL_M = 0;

    private final int version;
    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private Qr(int version) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];
    }

    /** The dark (true) and light modules of the symbol, without the quiet zone, as [y][x]. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (true) {
            if (version > 10) throw new IllegalArgumentException("Text too long for a QR code");
            int countBits = version <= 9 ? 8 : 16;
            if (4 + countBits + data.length * 8 <= dataCodewords(version) * 8) break;
            version++;
        }
        Qr qr = new Qr(version);
        qr.drawFunctionPatterns();
        qr.drawCodewords(qr.addEccAndInterleave(qr.dataBytes(data)));
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            qr.applyMask(mask);
            qr.drawFormatBits(mask);
            long penalty = qr.penalty();
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = mask;
            }
            qr.applyMask(mask); // undo, the mask is its own inverse
        }
        qr.applyMask(best);
        qr.drawFormatBits(best);
        return qr.modules;
    }

    // ---------------------------------------------------------------- sizes

    static int rawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    static int dataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    // ---------------------------------------------------------------- data

    private byte[] dataBytes(byte[] data) {
        List<Integer> bits = new ArrayList<>();
        appendBits(bits, 0b0100, 4); // byte mode
        appendBits(bits, data.length, version <= 9 ? 8 : 16);
        for (byte b : data) appendBits(bits, b & 0xFF, 8);
        int capacity = dataCodewords(version) * 8;
        appendBits(bits, 0, Math.min(4, capacity - bits.size()));
        appendBits(bits, 0, (8 - bits.size() % 8) % 8);
        for (int pad = 0xEC; bits.size() < capacity; pad ^= 0xEC ^ 0x11) appendBits(bits, pad, 8);
        byte[] out = new byte[bits.size() / 8];
        for (int i = 0; i < bits.size(); i++) out[i >>> 3] |= (byte) (bits.get(i) << (7 - (i & 7)));
        return out;
    }

    private static void appendBits(List<Integer> bits, int value, int length) {
        for (int i = length - 1; i >= 0; i--) bits.add((value >>> i) & 1);
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = NUM_BLOCKS[version];
        int blockEccLen = ECC_PER_BLOCK[version];
        int rawCodewords = rawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;
        byte[] divisor = rsDivisor(blockEccLen);
        List<byte[]> blocks = new ArrayList<>();
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int dataLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = Arrays.copyOfRange(data, k, k + dataLen);
            k += dataLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortBlockLen + 1];
            // Short blocks get a placeholder byte so every block has the same layout.
            System.arraycopy(dat, 0, block, 0, dat.length);
            System.arraycopy(ecc, 0, block, block.length - blockEccLen, blockEccLen);
            blocks.add(block);
        }
        byte[] result = new byte[rawCodewords];
        int n = 0;
        for (int i = 0; i < blocks.get(0).length; i++) {
            for (int j = 0; j < blocks.size(); j++) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[n++] = blocks.get(j)[i];
            }
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMul(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    // ---------------------------------------------------------------- drawing

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = alignmentPositions();
        for (int i = 0; i < align.length; i++) {
            for (int j = 0; j < align.length; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == align.length - 1) || (i == align.length - 1 && j == 0);
                if (!corner) drawAlignment(align[i], align[j]);
            }
        }
        drawFormatBits(0); // reserves the area; drawn again after masking
        drawVersion();
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int numAlign = version / 7 + 2;
        int step = (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_LEVEL_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true); // the dark module
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean dark = bit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            setFunction(a, b, dark);
            setFunction(b, a, dark);
        }
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Rules 1, 2 and 4 of the standard penalty; enough to avoid hard to read masks. */
    private long penalty() {
        long result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean prev = pass == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    boolean cur = pass == 0 ? modules[a][b] : modules[b][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) result += 3;
                        else if (run > 5) result++;
                    } else {
                        run = 1;
                    }
                }
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        return result + Math.max(0, k) * 10L;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    /** PNG with a 4 module quiet zone. */
    static byte[] png(boolean[][] qr, int scale) throws IOException {
        int border = 4;
        int dim = (qr.length + border * 2) * scale;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(dim, dim, java.awt.image.BufferedImage.TYPE_BYTE_BINARY);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, dim, dim);
        g.setColor(java.awt.Color.BLACK);
        for (int y = 0; y < qr.length; y++) {
            for (int x = 0; x < qr.length; x++) {
                if (qr[y][x]) g.fillRect((x + border) * scale, (y + border) * scale, scale, scale);
            }
        }
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

}
