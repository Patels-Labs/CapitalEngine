package com.patelheet.capitalengine.core.activity;

import com.patelheet.capitalengine.core.CoreConfig;
import com.patelheet.capitalengine.core.ledger.Activity;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.storage.PlayerRepo;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Who has played (name directory, last seen), how many non-AFK minutes each player got today, and the dormant set.
 * Drives {@code Stats.activePlayersToday} and {@code activeMoneySupply}. No money is ever moved for dormant players.
 */
public final class ActivityTracker implements Listener {
    private static final int ACTIVE_MINUTES = 5;

    private final PlayerRepo repo;
    private final Ledger ledger;
    private final Executor async;
    private final Logger log;
    private volatile CoreConfig cfg;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private final Set<UUID> starterPaid = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastAction = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> minutes = new ConcurrentHashMap<>();
    private volatile long day;
    private volatile double pastSum;
    private volatile int pastDays;
    private volatile Hooks hooks = Hooks.NONE;

    /** What the plugin wants done when a player joins. */
    public interface Hooks {
        Hooks NONE = new Hooks() {};
        /** Called on the main thread after the account exists. */
        default void joined(Player p, boolean firstEver, boolean starterAlreadyPaid) {}
    }

    public ActivityTracker(PlayerRepo repo, Ledger ledger, Executor async, Logger log, CoreConfig cfg) {
        this.repo = repo; this.ledger = ledger; this.async = async; this.log = log; this.cfg = cfg;
    }

    public void hooks(Hooks h) { hooks = h; }
    public void config(CoreConfig c) { cfg = c; }

    /** Loads the directory and today's minutes. */
    public void load(long today) throws SQLException {
        day = today;
        for (var r : repo.all()) {
            names.put(r.uuid(), r.name());
            byName.put(r.name().toLowerCase(Locale.ROOT), r.uuid());
            lastSeen.put(r.uuid(), r.lastSeen());
            if (r.starterPaid()) starterPaid.add(r.uuid());
        }
        minutes.putAll(repo.activity(today));
        recomputePast();
        publish();
    }

    private void recomputePast() throws SQLException {
        var perDay = repo.activePlayersPerDay(day - 7, ACTIVE_MINUTES);
        perDay.remove(day);
        pastSum = perDay.values().stream().mapToInt(Integer::intValue).sum();
        pastDays = perDay.size();
    }

    // ---- directory --------------------------------------------------------------------------------------------

    public boolean isKnown(UUID id) { return names.containsKey(id); }
    public String nameOf(UUID id) { return names.get(id); }

    /** Case-insensitive; Bedrock players (Floodgate prefix, default ".") match with or without the prefix. */
    public UUID uuidOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        UUID id = byName.get(n);
        return id != null ? id : byName.get("." + n);
    }

    /** Adds a player we have only heard of (for example from {@code /ce import}) so {@code /pay} can find them. */
    public void discover(UUID id, String name, long lastPlayed) {
        if (name == null || names.containsKey(id)) return;
        names.put(id, name);
        byName.put(name.toLowerCase(Locale.ROOT), id);
        lastSeen.put(id, lastPlayed);
        async.execute(() -> { try { repo.join(id, name, lastPlayed); } catch (SQLException e) { log.warning("Could not save player " + name + ": " + e.getMessage()); } });
    }

    // ---- events -----------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        boolean firstEver = !p.hasPlayedBefore();
        String old = names.put(id, p.getName());
        if (old != null && !old.equalsIgnoreCase(p.getName())) byName.remove(old.toLowerCase(Locale.ROOT));
        byName.put(p.getName().toLowerCase(Locale.ROOT), id);
        lastSeen.put(id, now);
        lastAction.put(id, now);
        ledger.player(id, p.getName()).rename(p.getName());
        async.execute(() -> { try { repo.join(id, p.getName(), now); } catch (SQLException ex) { log.warning("Could not save player " + p.getName() + ": " + ex.getMessage()); } });
        hooks.joined(p, firstEver, starterPaid.contains(id));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        lastSeen.put(id, now);
        lastAction.remove(id);
        async.execute(() -> { try { repo.quit(id, now); } catch (SQLException ex) { log.warning("Could not save quit time: " + ex.getMessage()); } });
    }

    // Any of these means "the player is at the keyboard"; a last-action timestamp is all we keep.
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR) public void onMove(PlayerMoveEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR) public void onInteract(PlayerInteractEvent e) { touch(e.getPlayer().getUniqueId()); }
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR) public void onChat(AsyncChatEvent e) { touch(e.getPlayer().getUniqueId()); }

    private void touch(UUID id) { lastAction.put(id, System.currentTimeMillis()); }

    /** Test/simulator hook: credits active minutes without a real player. */
    public void recordActivity(UUID id, int mins) { minutes.merge(id, mins, Integer::sum); publish(); }

    public void markStarterPaid(UUID id) {
        if (!starterPaid.add(id)) return;
        async.execute(() -> { try { repo.starterPaid(id); } catch (SQLException ex) { log.warning("Could not save starter flag: " + ex.getMessage()); } });
    }

    // ---- minute tick and day rollover -------------------------------------------------------------------------

    /** Called every minute on the global scheduler. */
    public void tick() {
        long cutoff = System.currentTimeMillis() - cfg.afkAfterMinutes() * 60_000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long last = lastAction.get(p.getUniqueId());
            if (last != null && last >= cutoff) minutes.merge(p.getUniqueId(), 1, Integer::sum);
        }
        publish();
        save();
    }

    /** Rolls over to a new economic day (not called for catch-up days). */
    public void newDay(long newDay) {
        save();
        minutes.clear();
        day = newDay;
        async.execute(() -> { try { recomputePast(); } catch (SQLException e) { log.warning("Could not read activity history: " + e.getMessage()); } publish(); });
        publish();
    }

    public void save() {
        long d = day;
        Map<UUID, Integer> copy = new HashMap<>(minutes);
        if (copy.isEmpty()) return;
        async.execute(() -> { try { repo.saveActivity(d, copy); } catch (SQLException e) { log.warning("Could not save activity: " + e.getMessage()); } });
    }

    /** Synchronous save for shutdown. */
    public void saveNow() {
        try { repo.saveActivity(day, new HashMap<>(minutes)); } catch (SQLException e) { log.warning("Could not save activity: " + e.getMessage()); }
    }

    private void publish() {
        long cutoff = System.currentTimeMillis() - cfg.dormantAfterDays() * 86_400_000L;
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        Set<UUID> dormant = new HashSet<>();
        lastSeen.forEach((id, seen) -> { if (seen < cutoff && !online.contains(id)) dormant.add(id); });
        int today = (int) minutes.values().stream().filter(m -> m >= ACTIVE_MINUTES).count();
        long total = minutes.values().stream().mapToLong(Integer::longValue).sum();
        double avg = (pastSum + today) / (pastDays + 1);
        ledger.setActivity(new Activity(today, total, avg, Set.copyOf(dormant)));
    }
}
