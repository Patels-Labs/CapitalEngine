package com.patelheet.capitalengine.dashboard.web;

import com.patelheet.capitalengine.dashboard.storage.Store;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.LongSupplier;

/**
 * Web login. {@code /cdash web} makes a one-time code (128 random bits, 5 minutes, single use); {@code /login?code=} trades it
 * for a session token (sent as an HttpOnly cookie, valid 7 days, only its SHA-256 stored). At most 10 failed logins per IP per hour.
 */
public final class Auth {
    public static final long CODE_MS = 5 * 60_000L, SESSION_MS = 7 * 86_400_000L, FAIL_WINDOW_MS = 3_600_000L;
    public static final int MAX_FAILS = 10;

    public enum Status { OK, BAD, LIMITED }

    public record Login(Status status, String token) {}

    private record Code(String player, long expires) {}

    private final Store store;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> fails = new ConcurrentHashMap<>();

    public Auth(Store store, LongSupplier clock) { this.store = store; this.clock = clock; }

    /** A fresh one-time code for {@code player} (a UUID, or "console"). */
    public String newCode(String player) {
        long now = clock.getAsLong();
        codes.values().removeIf(c -> c.expires() <= now);
        byte[] b = new byte[16];
        random.nextBytes(b);
        String code = HexFormat.of().formatHex(b);
        codes.put(code, new Code(player, now + CODE_MS));
        return code;
    }

    /** Trades a code for a session token. The code is used up whether or not it was still valid. */
    public Login login(String code, String ip) {
        long now = clock.getAsLong();
        if (limited(ip, now)) return new Login(Status.LIMITED, null);
        Code c = code == null ? null : codes.remove(code);   // atomic: two requests can't both use one code
        if (c == null || c.expires() <= now) {
            fails.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>()).add(now);
            return new Login(Status.BAD, null);
        }
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        store.saveSession(sha256(token), c.player(), now, now + SESSION_MS, ip);
        return new Login(Status.OK, token);
    }

    private boolean limited(String ip, long now) {
        fails.values().forEach(d -> d.removeIf(t -> t <= now - FAIL_WINDOW_MS));
        fails.values().removeIf(Deque::isEmpty);
        Deque<Long> d = fails.get(ip);
        if (d == null) return false;
        return d.size() >= MAX_FAILS;
    }

    /** Who a session token belongs to, or null if it is missing, unknown or expired. */
    public String player(String token) {
        if (token == null || token.isEmpty()) return null;
        return store.session(sha256(token), clock.getAsLong());
    }

    /** The CSRF token the page must echo in {@code X-CSRF} to acknowledge alerts; derived from the session token. */
    public static String csrf(String token) { return sha256("csrf:" + token); }

    /** Constant-time comparison. */
    public static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
