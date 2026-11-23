package com.patelheet.capitalengine.dashboard;

import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.dashboard.storage.Store;
import com.patelheet.capitalengine.dashboard.web.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Delivers new alerts: the console always, staff in game ({@code capitaldash.alerts}) from a minimum severity, and an optional
 * Discord webhook. Discord gets at most one message every 10 seconds; when the queue is full INFO alerts are dropped first,
 * and a failed post is logged once and pauses the webhook for 10 minutes.
 */
final class Notifier {
    private static final int QUEUE_MAX = 20;
    private static final long BACKOFF_MS = 10 * 60_000L;

    private final Plugin plugin;
    private final Messages msg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Deque<Store.Alert> discord = new ArrayDeque<>();
    private volatile Severity staffMin = Severity.WARN, discordMin = Severity.WARN;
    private volatile String webhook = "";
    private volatile long pausedUntil;

    Notifier(Plugin plugin, Messages msg) {
        this.plugin = plugin;
        this.msg = msg;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> sendOne(), 10, 10, TimeUnit.SECONDS);
    }

    void configure(Severity staffMin, String webhook, Severity discordMin) {
        this.staffMin = staffMin; this.webhook = webhook == null ? "" : webhook.trim(); this.discordMin = discordMin;
    }

    void deliver(Store.Alert a) {
        Level level = switch (a.severity()) { case INFO -> Level.INFO; case WARN -> Level.WARNING; case CRITICAL -> Level.SEVERE; };
        plugin.getLogger().log(level, "Alert #" + a.id() + " [" + a.source() + "] " + a.code() + ": " + a.message());
        if (a.severity().compareTo(staffMin) >= 0) {
            var line = msg.get("alert-line", "source", a.source(), "message", a.message(), "id", a.id());
            Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
                for (var p : Bukkit.getOnlinePlayers()) if (p.hasPermission("capitaldash.alerts")) p.sendMessage(line);
            });
        }
        if (!webhook.isEmpty() && a.severity().compareTo(discordMin) >= 0) {
            synchronized (discord) {
                if (discord.size() >= QUEUE_MAX && !discord.removeIf(x -> x.severity() == Severity.INFO)) discord.pollFirst();
                discord.addLast(a);
            }
        }
    }

    private void sendOne() {
        if (webhook.isEmpty() || System.currentTimeMillis() < pausedUntil) return;
        Store.Alert a;
        synchronized (discord) { a = discord.pollFirst(); }
        if (a == null) return;
        int colour = switch (a.severity()) { case INFO -> 0x3498DB; case WARN -> 0xF1C40F; case CRITICAL -> 0xE74C3C; };
        String body = Json.write(Map.of(
                "allowed_mentions", Map.of("parse", List.of()),   // alert text comes from other plugins: never ping anyone
                "embeds", List.of(Map.of("title", msg.plain("discord-title", "source", a.source()), "description", a.message(), "color", colour,
                        "footer", Map.of("text", a.code() + " · #" + a.id())))));
        try {
            var req = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 300) throw new java.io.IOException("HTTP " + status);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            pausedUntil = System.currentTimeMillis() + BACKOFF_MS;
            plugin.getLogger().warning("Discord webhook failed (" + e.getMessage() + "); trying again in 10 minutes.");
        }
    }
}
