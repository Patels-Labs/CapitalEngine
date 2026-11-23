package com.patelheet.capitalengine.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.Currency;
import com.patelheet.capitalengine.api.PluginFlow;
import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.dashboard.alerts.BuiltInRules;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import com.patelheet.capitalengine.dashboard.storage.Store;
import com.patelheet.capitalengine.dashboard.web.Auth;
import com.patelheet.capitalengine.dashboard.web.JsonApi;
import com.patelheet.capitalengine.dashboard.web.WebServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Login codes and sessions, and the web server's default-deny: without a session (or the metrics token) nothing is readable. */
class WebTest {
    static final Currency COINS = new Currency() {
        public String singular() { return "coin"; }
        public String plural() { return "coins"; }
        public String symbol() { return "⛁"; }
        public int decimals() { return 2; }
        public String format(long amount) { return amount / 100 + " coins"; }
        public long parse(String text) { return Math.round(Double.parseDouble(text) * 100); }
    };
    static final String SECRET_NUMBER = "987654321";   // the money supply; must never appear in an unauthenticated response

    @TempDir Path dir;
    Store store;
    AtomicLong clock = new AtomicLong(1_000_000_000L);
    Auth auth;
    WebServer web;
    HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach void start() throws Exception {
        store = StoreTest.sqlite(dir);
        auth = new Auth(store, clock::get);
        var snap = Snapshot.of(clock.get(), PureLogicTest.stats(Long.parseLong(SECRET_NUMBER), 0, 10, Map.of("Jobs", new PluginFlow(10, 0, 0, 0, 0))), List.of(), f -> f);
        store.saveSnapshot(snap);
        store.insertAlert(clock.get(), "Jobs", "dash.plugin_printer", Severity.WARN, "Jobs " + SECRET_NUMBER);
        var api = new JsonApi(store, () -> snap, COINS, () -> new BuiltInRules.Thresholds(3, 0.5, 5, 0.2), clock::get);
        web = new WebServer(auth, api, (id, by) -> store.ack(id, by, clock.get()), true, "metrics-secret", 2, store::openAlerts, Logger.getLogger("test"));
        web.start("127.0.0.1", 0);
    }

    @AfterEach void stop() { web.stop(); store.close(); }

    HttpResponse<String> get(String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path)).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/x-www-form-urlencoded");
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static final List<String> DATA = List.of("/api/summary", "/api/series?metric=core.moneySupply&range=30d", "/api/series?metric=core.moneySupply&daily=1",
            "/api/metrics", "/api/plugins?range=7d", "/api/alerts?open=true", "/api/alerts?open=false", "/api/unknown", "/api", "/metrics/", "/login/../api/summary");

    @Test void nothingIsReadableWithoutASession() throws Exception {
        for (String path : DATA) for (String[] h : new String[][]{{}, {"Cookie", "cdash_session=garbage"}, {"Cookie", "other=1"}, {"Authorization", "Bearer metrics-secret"}}) {
            var r = get(path, h);
            assertEquals(401, r.statusCode(), path + " with " + List.of(h));
            assertFalse(r.body().contains(SECRET_NUMBER), path);
            assertFalse(r.body().contains("Jobs"), path);
        }
        var ack = post("/api/ack", "id=all");
        assertEquals(401, ack.statusCode());
        assertEquals(1, store.openAlerts(), "an unauthenticated ack changes nothing");
    }

    @Test void metricsNeedTheBearerToken() throws Exception {
        for (String[] h : new String[][]{{}, {"Authorization", "Bearer wrong"}, {"Authorization", "metrics-secret"}, {"Authorization", "Basic bWV0cmljcy1zZWNyZXQ="}}) {
            var r = get("/metrics", h);
            assertEquals(401, r.statusCode());
            assertFalse(r.body().contains("capital_"), "no data without the token");
        }
        var ok = get("/metrics", "Authorization", "Bearer metrics-secret");
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().contains("capital_core_money_supply 9876543.21\n"), ok.body());
        assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"));
    }

    @Test void staticFilesAreOpenButCarryNoData() throws Exception {
        for (String p : List.of("/", "/app.js", "/style.css")) {
            var r = get(p);
            assertEquals(200, r.statusCode(), p);
            assertFalse(r.body().contains(SECRET_NUMBER));
        }
        assertEquals(401, get("/web/index.html").statusCode(), "paths are not mapped onto jar resources");
        assertEquals(401, get("/plugin.yml").statusCode());
        assertEquals("default-src 'self'; img-src 'self' data:; frame-ancestors 'none'", get("/").headers().firstValue("Content-Security-Policy").orElse(""));
    }

    String login() throws Exception {
        String code = auth.newCode("console");
        var r = get("/login?code=" + code);
        assertEquals(302, r.statusCode());
        String cookie = r.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict") && cookie.contains("Max-Age=604800"), cookie);
        return cookie.substring(0, cookie.indexOf(';'));
    }

    @Test void aSessionReadsEverythingAndAckNeedsTheCsrfToken() throws Exception {
        String cookie = login();
        var summary = get("/api/summary", "Cookie", cookie);
        assertEquals(200, summary.statusCode());
        assertTrue(summary.body().contains("\"moneySupply\":" + SECRET_NUMBER), summary.body());
        for (String path : DATA.subList(1, 7)) assertEquals(200, get(path, "Cookie", cookie).statusCode(), path);
        assertTrue(get("/api/plugins?range=1d", "Cookie", cookie).body().contains("\"printer\":true"));
        assertEquals(404, get("/api/unknown", "Cookie", cookie).statusCode());
        assertEquals(400, get("/api/series", "Cookie", cookie).statusCode());

        String csrf = summary.body().replaceAll(".*\"csrf\":\"([0-9a-f]+)\".*", "$1");
        assertEquals(403, post("/api/ack", "id=all", "Cookie", cookie).statusCode(), "no CSRF header");
        assertEquals(403, post("/api/ack", "id=all", "Cookie", cookie, "X-CSRF", "0".repeat(64)).statusCode());
        assertEquals(1, store.openAlerts());
        var ok = post("/api/ack", "id=all", "Cookie", cookie, "X-CSRF", csrf);
        assertEquals(200, ok.statusCode());
        assertEquals("{\"acked\":1}", ok.body());
        assertEquals(0, store.openAlerts());
        assertEquals("console", store.alerts(false, 1).get(0).ackedBy());
    }

    @Test void codesAreSingleUseAndExpire() throws Exception {
        String code = auth.newCode("console");
        assertEquals(Auth.Status.OK, auth.login(code, "1.1.1.1").status());
        assertEquals(Auth.Status.BAD, auth.login(code, "1.1.1.1").status(), "a code works once");
        String late = auth.newCode("console");
        clock.addAndGet(Auth.CODE_MS);
        assertEquals(Auth.Status.BAD, auth.login(late, "1.1.1.1").status(), "codes last 5 minutes");
        assertEquals(403, get("/login?code=" + late).statusCode());
        assertEquals(403, get("/login").statusCode());

        var s = auth.login(auth.newCode("p"), "1.1.1.1");
        assertEquals("p", auth.player(s.token()));
        clock.addAndGet(Auth.SESSION_MS);
        assertNull(auth.player(s.token()), "sessions last 7 days");
        assertNull(auth.player(null));
        assertNull(auth.player(""));
    }

    @Test void tenFailedLoginsPerIpPerHour() {
        for (int i = 0; i < Auth.MAX_FAILS; i++) assertEquals(Auth.Status.BAD, auth.login("wrong" + i, "2.2.2.2").status());
        String good = auth.newCode("console");
        assertEquals(Auth.Status.LIMITED, auth.login(good, "2.2.2.2").status(), "even a good code is refused while limited");
        assertEquals(Auth.Status.OK, auth.login(auth.newCode("console"), "3.3.3.3").status(), "other addresses are not affected");
        clock.addAndGet(Auth.FAIL_WINDOW_MS);
        assertEquals(Auth.Status.OK, auth.login(auth.newCode("console"), "2.2.2.2").status(), "the limit lifts after an hour");
    }

    @Test void onlyTheTokenHashIsStored() {
        var s = auth.login(auth.newCode("console"), "1.1.1.1");
        assertNull(store.session(s.token(), clock.get()));
        assertEquals("console", store.session(Auth.sha256(s.token()), clock.get()));
    }
}
