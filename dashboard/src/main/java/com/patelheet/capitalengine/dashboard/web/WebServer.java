package com.patelheet.capitalengine.dashboard.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The local web page and its read-only JSON API, on the JDK's HttpServer with a pool of 4 threads. One handler serves every
 * path and denies by default: only the three static files and {@code /login} are open, {@code /metrics} needs the bearer
 * token, and everything else needs a session cookie. The only POST is acknowledging alerts, which also needs the CSRF token.
 */
public final class WebServer {
    private static final Map<String, String> STATIC = Map.of("/", "web/index.html", "/index.html", "web/index.html",
            "/app.js", "web/app.js", "/style.css", "web/style.css");
    private static final String COOKIE = "cdash_session";

    private final Auth auth;
    private final JsonApi api;
    private final BiFunction<Long, String, Integer> acker;   // (alert id or null for all, who) → how many were acknowledged
    private final boolean webEnabled;
    private final String metricsToken;   // null: /metrics is off
    private final IntSupplier openAlerts;
    private final int decimals;
    private final Logger log;
    private HttpServer server;
    private ExecutorService pool;

    public WebServer(Auth auth, JsonApi api, BiFunction<Long, String, Integer> acker, boolean webEnabled, String metricsToken, int decimals, IntSupplier openAlerts, Logger log) {
        this.auth = auth; this.api = api; this.acker = acker; this.webEnabled = webEnabled; this.metricsToken = metricsToken;
        this.decimals = decimals; this.openAlerts = openAlerts; this.log = log;
    }

    /** Binds and starts. Port 0 picks a free port (tests). */
    public void start(String bind, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "CapitalDashboard-web"); t.setDaemon(true); return t; });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) { pool.shutdownNow(); try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        server = null;
        pool = null;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            var h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Cache-Control", "no-store");
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'");
            route(ex);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Dashboard web request failed: " + e.getMessage(), e);
            try { send(ex, 500, "application/json", "{\"error\":\"server\"}"); } catch (IOException ignored) { }
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        if (path.equals("/metrics") && metricsToken != null) { metrics(ex); return; }
        if (!webEnabled) { send(ex, 404, "text/plain", "Not found"); return; }
        if (method.equals("GET") && STATIC.containsKey(path)) { file(ex, STATIC.get(path)); return; }
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        if (path.equals("/login") && method.equals("GET")) { login(ex, q); return; }

        String token = cookie(ex);
        String who = auth.player(token);
        if (who == null) { send(ex, 401, "application/json", "{\"error\":\"login\"}"); return; }
        Object body = switch (method + " " + path) {
            case "GET /api/summary" -> { var m = api.summary(); m.put("csrf", Auth.csrf(token)); yield m; }
            case "GET /api/series" -> q.get("metric") == null ? null : api.series(q.get("metric"), q.get("range"), "1".equals(q.get("daily")) || "true".equals(q.get("daily")));
            case "GET /api/metrics" -> api.catalogue();
            case "GET /api/plugins" -> api.plugins(q.get("range"));
            case "GET /api/alerts" -> api.alerts(!"false".equals(q.get("open")));
            case "POST /api/ack" -> {
                if (!Auth.same(Auth.csrf(token), ex.getRequestHeaders().getFirst("X-CSRF"))) { send(ex, 403, "application/json", "{\"error\":\"csrf\"}"); yield NO; }
                String id = query(new String(ex.getRequestBody().readNBytes(256), StandardCharsets.UTF_8)).get("id");
                if (id == null) yield null;
                try { yield JsonApi.map("acked", acker.apply(id.equals("all") ? null : Long.parseLong(id), who)); }
                catch (NumberFormatException e) { yield null; }
            }
            default -> NOT_FOUND;
        };
        if (body == NO) return;
        if (body == NOT_FOUND) send(ex, 404, "application/json", "{\"error\":\"not found\"}");
        else if (body == null) send(ex, 400, "application/json", "{\"error\":\"bad request\"}");
        else send(ex, 200, "application/json", Json.write(body));
    }

    private static final Object NO = new Object(), NOT_FOUND = new Object();

    private void metrics(HttpExchange ex) throws IOException {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ") || !Auth.same(metricsToken, header.substring(7).trim())) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            send(ex, 401, "text/plain", "Unauthorized");
            return;
        }
        var s = api.latest();
        send(ex, 200, "text/plain; version=0.0.4", s == null ? "" : Metrics.render(s, decimals, openAlerts.getAsInt()));
    }

    private void login(HttpExchange ex, Map<String, String> q) throws IOException {
        // ponytail: behind a reverse proxy every visitor has the proxy's address and shares one failure limit; trust X-Forwarded-For from a configured proxy if that ever bites
        Auth.Login r = auth.login(q.get("code"), ex.getRemoteAddress().getAddress().getHostAddress());   // never a forwarded header: it can be forged
        switch (r.status()) {
            case OK -> {
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.token() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + Auth.SESSION_MS / 1000);
                ex.getResponseHeaders().set("Location", "/");
                send(ex, 302, "text/plain", "");
            }
            case LIMITED -> send(ex, 429, "text/plain", "Too many failed logins. Try again in an hour.");
            case BAD -> send(ex, 403, "text/plain", "This login link is used up or has expired. Type /cdash web in game for a new one.");
        }
    }

    private void file(HttpExchange ex, String resource) throws IOException {
        try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) { send(ex, 404, "text/plain", "Not found"); return; }
            String type = resource.endsWith(".js") ? "text/javascript" : resource.endsWith(".css") ? "text/css" : "text/html";
            send(ex, 200, type + "; charset=utf-8", in.readAllBytes());
        }
    }

    private static String cookie(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of()))
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) return p.substring(COOKIE.length() + 1);
            }
        return null;
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try { m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException ignored) { }
        }
        return m;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException { send(ex, status, type, body.getBytes(StandardCharsets.UTF_8)); }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type.contains("charset") ? type : type + "; charset=utf-8");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream out = ex.getResponseBody()) { out.write(body); }
    }
}
