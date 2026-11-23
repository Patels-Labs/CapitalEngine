package com.patelheet.capitalengine.bank;

import com.patelheet.capitalengine.api.AccountKind;
import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalTransactionEvent;
import com.patelheet.capitalengine.api.event.CapitalWeekEvent;
import com.patelheet.capitalengine.api.service.Indicator;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.bank.storage.Store;
import com.patelheet.capitalengine.bank.upkeep.ClaimSource;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires CapitalBank together. The bank itself lives in {@link BankService}. */
public class CapitalBankPlugin extends JavaPlugin implements Listener {
    private BankService service;
    private Actions actions;
    private Menus menus;

    @Override public void onEnable() {
        CapitalApi api = getServer().getServicesManager().load(CapitalApi.class);
        if (api == null) { getLogger().severe("CapitalEngine's API isn't available."); getServer().getPluginManager().disablePlugin(this); return; }
        FileUpdater.sync(this, "config.yml");   // new settings arrive on upgrade; the owner's values are never changed
        reloadConfig();
        Messages msg = new Messages(this);
        try {
            service = new BankService(this, api, openStore(), msg, getConfig());
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not open bank.db", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getServicesManager().register(IndicatorProvider.class, new IndicatorProvider() {
            @Override public String id() { return "capitalbank"; }
            @Override public String displayName() { return "Bank"; }
            @Override public List<Indicator> indicators() { return service.indicators; }
        }, this, ServicePriority.Normal);
        actions = new Actions(this, service, msg);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new Menu.Guard(), this);
        menus = new Menus(actions, service, msg);
        var cmd = new BankCommand(this, service, actions, menus, msg);
        getCommand("bank").setExecutor(cmd);
        getCommand("bank").setTabCompleter(cmd);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) new BankExpansion(service).register();
        // next tick: every plugin (and so every claim plugin) is enabled
        getServer().getGlobalRegionScheduler().run(this, t -> {
            service.sources.addAll(ClaimSource.installed(this));
            service.recover(snapshot());
        });
    }

    /** Same {@code storage:} keys as CapitalEngine; MySQL and PostgreSQL tables get the {@code prefix} so a database can be shared. */
    private Store openStore() throws java.sql.SQLException {
        var s = getConfig().getConfigurationSection("storage");
        String type = s == null ? "sqlite" : s.getString("type", "sqlite").toLowerCase(), host = s == null ? "localhost" : s.getString("host", "localhost");
        String name = s == null ? "capitalbank" : s.getString("database", "capitalbank"), user = s == null ? "" : s.getString("user", ""), pass = s == null ? "" : s.getString("password", "");
        int port = s == null ? 3306 : s.getInt("port", type.startsWith("postgres") ? 5432 : 3306), pool = s == null ? 0 : s.getInt("pool-size", 0);
        String prefix = s == null ? "" : s.getString("prefix", type.equals("sqlite") ? "" : "bank_");
        return switch (type) {
            case "sqlite" -> new Store(Store.Dialect.SQLITE, "jdbc:sqlite:" + new File(getDataFolder(), s == null ? "bank.db" : s.getString("file", "bank.db")).getAbsolutePath(), null, null, pool > 0 ? pool : 2, prefix);
            case "mysql", "mariadb" -> new Store(Store.Dialect.MYSQL, "jdbc:mysql://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=utf8", user, pass, pool > 0 ? pool : 4, prefix);
            case "postgres", "postgresql" -> new Store(Store.Dialect.POSTGRES, "jdbc:postgresql://" + host + ":" + port + "/" + name, user, pass, pool > 0 ? pool : 4, prefix);
            default -> throw new IllegalArgumentException("storage.type must be sqlite, mysql, mariadb or postgres (got '" + type + "')");
        };
    }

    /** For tests and the simulator. */
    public BankService service() { return service; }

    Actions actions() { return actions; }

    Menus menus() { return menus; }

    @Override public void onDisable() { if (service != null) service.close(); }

    // ---- main-thread reads the worker needs ----------------------------------------------------------------------

    /** Everyone's claimed area (summed over every claim plugin) and who is online. Main thread. */
    BankService.Snapshot snapshot() {
        Map<UUID, Long> areas = new HashMap<>();
        for (ClaimSource src : service.sources) {
            try { src.areas().forEach((k, v) -> areas.merge(k, v, Long::sum)); }
            catch (RuntimeException e) { getLogger().warning("Could not read claims (" + e + "); that plugin's land counts as none today."); }
        }
        Set<UUID> online = new HashSet<>();
        getServer().getOnlinePlayers().forEach(p -> online.add(p.getUniqueId()));
        return new BankService.Snapshot(areas, online);
    }

    /** One player's claimed area right now. Main thread. */
    long areaOf(UUID p) {
        long a = 0;
        for (ClaimSource src : service.sources) { try { a += src.areas().getOrDefault(p, 0L); } catch (RuntimeException ignored) { } }
        return a;
    }

    /** Active playtime in minutes, from the vanilla statistic. ponytail: core's activity tracking isn't in the API; switch to it once it is. */
    static long playedMinutes(Player p) { return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / (20 * 60); }

    // ---- events ------------------------------------------------------------------------------------------------

    @EventHandler public void onDay(CapitalDayEvent e) { service.onDay(e.dayIndex(), e.catchUp(), snapshot()); }

    @EventHandler public void onWeek(CapitalWeekEvent e) { service.onWeek(e.catchUp()); }

    @EventHandler public void onJoin(PlayerJoinEvent e) { service.joined(e.getPlayer().getUniqueId()); }

    @EventHandler public void onQuit(PlayerQuitEvent e) { service.quit(e.getPlayer().getUniqueId()); }

    /** Coins arriving in the wallet of someone who owes upkeep or is behind on a loan are taken (async event). The bank's own payouts are left alone. */
    @EventHandler public void onTransaction(CapitalTransactionEvent e) {
        if ((service.upkeepDue.isEmpty() && service.behind.isEmpty()) || getName().equalsIgnoreCase(e.record().sourcePlugin())) return;
        for (Posting x : e.record().postings()) {
            if (x.amount() <= 0) continue;
            service.api.accounts().byId(x.accountId()).filter(a -> a.kind() == AccountKind.PLAYER).flatMap(a -> a.ownerPlayer())
                    .filter(service::owes).ifPresent(service::coinsArrived);
        }
    }

    /** A player answering a chat prompt ("how many coins?") isn't talking to the server. */
    @EventHandler(priority = EventPriority.LOWEST) public void onChat(AsyncChatEvent e) {
        if (actions.answer(e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()))) e.setCancelled(true);
    }

    // ---- frozen land (§5.4): building, breaking and opening containers are blocked for the owner and trusted players ----------

    /** Whether {@code p} may not use the land at {@code loc}: its owner is frozen and {@code p} is that owner or trusted there. Visitors aren't affected. */
    boolean frozenFor(Player p, Location loc) {
        if (service.frozen.isEmpty() || loc == null) return false;
        for (ClaimSource src : service.sources) {
            UUID owner = src.ownerAt(loc);
            if (owner != null && service.frozen.contains(owner) && (owner.equals(p.getUniqueId()) || src.trusted(loc, p.getUniqueId()))) return true;
        }
        return false;
    }

    private void block(Cancellable e, Player p, Location loc) {
        if (!frozenFor(p, loc)) return;
        e.setCancelled(true);
        actions.say(p, "land-frozen");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { block(e, e.getPlayer(), e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { block(e, e.getPlayer(), e.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onOpen(InventoryOpenEvent e) {
        // block containers only (chests, barrels, furnaces, double chests); menus have no location and entities aren't land
        if (e.getPlayer() instanceof Player p && !(e.getInventory().getHolder(false) instanceof org.bukkit.entity.Entity)) block(e, p, e.getInventory().getLocation());
    }
}
