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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    static final Set<String> sessions = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        List<String> flags = Arrays.asList(args);
        // The protection agent on a managed PC (run by Windows as SYSTEM).
        if (flags.contains("--agent")) {
            PcUser.runAgent();
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
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
        } catch (BindException e) {
            // Already running: a second launch just brings up the window.
            System.out.println(APP_NAME + " is already running on port " + PORT + ".");
            if (desktopApp && showWindow) openWindow();
            return;
        }
        server.createContext("/", CurboxAdmin::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println(APP_NAME + " is running. Data: " + DATA);
        System.out.println("Admin page: http://localhost:" + PORT);
        for (String address : lanAddresses()) {
            System.out.println("Device address: " + address);
        }

        if (desktopApp) startTray(server, showWindow);
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

    static void startTray(HttpServer server, boolean showWindow) {
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

            // Back to the Admin or User choice, for a computer set up with the wrong role.
            MenuItem changeRole = new MenuItem("Change role");
            changeRole.addActionListener(e -> {
                PcUser.writeMode(PcUser.MODE_NONE);
                SystemTray.getSystemTray().remove(icon);
                server.stop(0);
                PcUser.runSetup(null);
            });
            menu.add(changeRole);

            MenuItem quit = new MenuItem("Quit");
            quit.addActionListener(e -> {
                SystemTray.getSystemTray().remove(icon);
                server.stop(0);
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
                    case "/api/admin/overview":
                        if (requireAdmin(ex)) adminOverview(ex);
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
                    default:
                        sendJson(ex, 404, error("not_found"));
                        return;
                }
            }

            sendJson(ex, 405, error("method_not_allowed"));
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

        boolean matches;
        synchronized (LOCK) {
            Map<String, Object> user = findIn(users, "username", username);
            matches = user != null && sha256(password).equals(user.get("passwordHash"));
        }
        if (!matches) {
            slowDown();
            sendJson(ex, 403, error("wrong_login"));
            return;
        }

        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            Map<String, Object> device = findIn(devices, "id", deviceId);
            if (device == null) {
                device = new LinkedHashMap<>();
                device.put("id", deviceId);
                device.put("apps", new ArrayList<>());
                device.put("blockedApps", new ArrayList<>());
                device.put("blockedSites", new ArrayList<>());
                device.put("linkedAt", now);
                devices.add(device);
            }
            String token = randomHex(16);
            device.put("user", username);
            device.put("platform", platform);
            device.put("name", name.isEmpty() ? ("windows".equals(platform) ? "Computer" : "Phone") : name);
            device.put("token", token);
            device.put("lastSeen", now);
            save();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", token);
            out.put("username", username);
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
                sendJson(ex, 401, error("unlinked"));
                return;
            }
            boolean changed = false;

            String name = limit(str(body, "deviceName").trim(), 60);
            if (!name.isEmpty() && !name.equals(device.get("name"))) {
                device.put("name", name);
                changed = true;
            }
            String platform = str(body, "platform").trim();
            if (!platform.isEmpty()) device.put("platform", normalizePlatform(platform));
            String version = limit(str(body, "appVersion").trim(), 40);
            if (!version.isEmpty()) device.put("appVersion", version);

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
                device.put("apps", cleaned);
                changed = true;
            }

            device.put("lastSeen", System.currentTimeMillis());
            if (changed) save();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("username", device.get("user"));
            out.put("blockedApps", device.get("blockedApps"));
            out.put("blockedSites", device.get("blockedSites"));
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
        boolean matches;
        synchronized (LOCK) {
            matches = username.equals(adminUser) && adminHash.equals(sha256(password));
        }
        if (!matches) {
            slowDown();
            sendJson(ex, 403, error("wrong_login"));
            return;
        }
        startSession(ex);
        sendJson(ex, 200, ok());
    }

    static void adminChangePassword(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String current = str(body, "currentPassword");
        String next = str(body, "newPassword");
        synchronized (LOCK) {
            if (!adminHash.equals(sha256(current))) {
                sendJson(ex, 403, error("wrong_password"));
                return;
            }
            if (next.length() < 4) {
                sendJson(ex, 400, error("too_short"));
                return;
            }
            adminHash = sha256(next);
            save();
        }
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
        sendJson(ex, 200, ok());
    }

    static void adminDeleteUser(HttpExchange ex) throws IOException {
        String username = normalizeUsername(str(readJsonObject(ex), "username"));
        synchronized (LOCK) {
            users.removeIf(u -> username.equals(u.get("username")));
            // Their phones lose the link, which lifts the blocks there too.
            devices.removeIf(d -> username.equals(d.get("user")));
            save();
        }
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
            user.put("passwordHash", sha256(password));
            save();
        }
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
                long lastSeen = num(device, "lastSeen");
                d.put("online", now - lastSeen < ONLINE_WINDOW_MS);
                list.add(d);
            }
            out.put("devices", list);
        }
        sendJson(ex, 200, out);
    }

    static void adminRules(HttpExchange ex) throws IOException {
        Map<String, Object> body = readJsonObject(ex);
        String deviceId = str(body, "deviceId");
        List<String> apps = cleanList(body.get("blockedApps"), false);
        List<String> sites = cleanList(body.get("blockedSites"), true);
        synchronized (LOCK) {
            Map<String, Object> device = findIn(devices, "id", deviceId);
            if (device == null) {
                sendJson(ex, 404, error("no_device"));
                return;
            }
            device.put("blockedApps", apps);
            device.put("blockedSites", sites);
            save();
        }
        sendJson(ex, 200, ok());
    }

    static void adminRemove(HttpExchange ex) throws IOException {
        String deviceId = str(readJsonObject(ex), "deviceId");
        synchronized (LOCK) {
            devices.removeIf(d -> deviceId.equals(d.get("id")));
            save();
        }
        sendJson(ex, 200, ok());
    }

    // ------------------------------------------------------------ admin auth

    static boolean isAdmin(HttpExchange ex) {
        String token = cookie(ex, SESSION_COOKIE);
        return !token.isEmpty() && sessions.contains(token);
    }

    static boolean requireAdmin(HttpExchange ex) throws IOException {
        if (isAdmin(ex)) return true;
        sendJson(ex, 401, error("login_required"));
        return false;
    }

    static void startSession(HttpExchange ex) {
        String token = randomHex(24);
        sessions.add(token);
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
    }

    // ----------------------------------------------------------- persistence

    @SuppressWarnings("unchecked")
    static void load() {
        // Safe to call again, for example after switching this computer back to admin.
        users.clear();
        devices.clear();
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
                }
            } catch (Exception e) {
                System.out.println("Could not read " + DATA + ": " + e);
            }
        }

        boolean seeded = false;
        if (adminUser.isEmpty() || adminHash.isEmpty()) {
            adminUser = DEFAULT_ADMIN_USER;
            adminHash = sha256(DEFAULT_ADMIN_PASSWORD);
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
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("adminUser", adminUser);
        root.put("adminHash", adminHash);
        root.put("users", users);
        root.put("devices", devices);
        try {
            Path tmp = DATA_DIR.resolve("curbox-admin-data.json.tmp");
            Files.write(tmp, Json.write(root).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, DATA, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("Could not save data: " + e);
        }
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
        user.put("passwordHash", sha256(password));
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

    /** "https://www.YouTube.com/watch" becomes "youtube.com/watch"; "www.x.com/" becomes "x.com". */
    static String normalizeSite(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        if (s.startsWith("www.")) s = s.substring(4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static List<String> lanAddresses() {
        List<String> preferred = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String entry = a.getHostAddress() + ":" + PORT;
                    if (a.isSiteLocalAddress()) preferred.add(entry);
                    else other.add(entry);
                }
            }
        } catch (Exception ignored) {
        }
        preferred.addAll(other);
        return preferred;
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
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
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

    /** Desktop shortcut on a managed PC: show the agent's status, or sign in again if needed. */
    static void openUserWindow() {
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

        ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);
        // A thrown error would silently stop a scheduled task, so each run is guarded.
        timer.scheduleWithFixedDelay(() -> safely("sync", PcUser::sync), 0, SYNC_MS, TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(() -> safely("enforce", PcUser::enforce), 1000, ENFORCE_MS, TimeUnit.MILLISECONDS);
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
        if (sendPrograms) {
            programs = scanPrograms();
            body.put("apps", programs);
        }

        Response r;
        try {
            r = httpPost(CurboxAdmin.str(current, "serverUrl") + "/api/device/sync",
                CurboxAdmin.Json.write(body), CurboxAdmin.str(current, "deviceToken"));
        } catch (IOException e) {
            // The admin computer is off or out of reach. The last rules keep working.
            syncState = "offline";
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

        List<String> apps = stringList(parsed.get("blockedApps"));
        List<String> sites = stringList(parsed.get("blockedSites"));
        boolean changed;
        synchronized (AGENT_LOCK) {
            if (link == null) return;
            changed = !apps.equals(stringList(link.get("blockedApps"))) || !sites.equals(stringList(link.get("blockedSites")));
            if (changed) {
                link.put("blockedApps", apps);
                link.put("blockedSites", sites);
                writeAtomic(linkFile(), CurboxAdmin.Json.write(link));
            }
        }
        if (changed) {
            applyRules();
            System.out.println("New rules: " + apps.size() + " programs, " + sites.size() + " websites.");
        }
        lastSyncMs = now;
        syncState = "connected";
    }

    static void applyRules() throws IOException {
        List<String> apps;
        List<String> sites;
        synchronized (AGENT_LOCK) {
            apps = link == null ? new ArrayList<>() : stringList(link.get("blockedApps"));
            sites = link == null ? new ArrayList<>() : stringList(link.get("blockedSites"));
        }
        Set<String> exes = new LinkedHashSet<>();
        for (String app : apps) {
            String exe = app.toLowerCase(Locale.ROOT);
            exe = exe.substring(Math.max(exe.lastIndexOf('\\'), exe.lastIndexOf('/')) + 1);
            if (!exe.endsWith(".exe")) exe = exe + ".exe";
            if (!ESSENTIAL.contains(exe)) exes.add(exe);
        }
        blockedExes = exes;
        writeHosts(sites);
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
            if (!isLocalRequest(ex, AGENT_PORT) || !"GET".equals(ex.getRequestMethod())) {
                CurboxAdmin.sendJson(ex, 403, CurboxAdmin.error("forbidden"));
                return;
            }
            String path = ex.getRequestURI().getPath();
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
        } catch (Exception e) {
            System.out.println("Agent page failed: " + e);
            CurboxAdmin.sendJson(ex, 500, CurboxAdmin.error("server_error"));
        } finally {
            ex.close();
        }
    }
}
