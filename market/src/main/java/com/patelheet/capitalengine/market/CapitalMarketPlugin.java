package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalWeekEvent;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.api.service.MonetaryPolicy;
import com.patelheet.capitalengine.market.maker.BukkitRecipes;
import com.patelheet.capitalengine.market.storage.Store;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires CapitalMarket together. The economy lives in {@link MarketService}. */
public class CapitalMarketPlugin extends JavaPlugin implements Listener {
    private MarketService service;
    private Actions actions;
    private Menus menus;
    private Stalls stalls;

    @Override public void onEnable() {
        CapitalApi api = getServer().getServicesManager().load(CapitalApi.class);
        if (api == null) { getLogger().severe("CapitalEngine's API isn't available."); getServer().getPluginManager().disablePlugin(this); return; }
        saveDefaultConfig();
        for (String f : new String[]{"values.yml", "server-items.yml", "projects.yml", "messages.yml"}) if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        Messages msg = new Messages(this);
        try {
            Store store = openStore();
            service = new MarketService(this, api, store, msg, getConfig(), YamlConfiguration.loadConfiguration(new File(getDataFolder(), "values.yml")),
                    YamlConfiguration.loadConfiguration(new File(getDataFolder(), "server-items.yml")).getStringList("items"), BukkitRecipes.load());
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not open market.db", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var policy = new MarketPolicyService(service);
        getServer().getServicesManager().register(MonetaryPolicy.class, policy, this, ServicePriority.Normal);
        getServer().getServicesManager().register(IndicatorProvider.class, policy, this, ServicePriority.Normal);

        actions = new Actions(this, service, msg);
        menus = new Menus(actions, service, msg);
        stalls = newStalls(service, menus);
        actions.stalls = stalls;
        stalls.load();
        for (Listener l : new Listener[]{this, new Menu.Guard(), stalls, service.tutorial}) getServer().getPluginManager().registerEvents(l, this);
        var cmd = new MarketCommand(this, service, actions, menus, stalls, msg);
        for (String name : new String[]{"market", "jobs", "economy"}) {
            getCommand(name).setExecutor(cmd);
            getCommand(name).setTabCompleter(cmd);
        }
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) new MarketExpansion(service).register();
        getServer().getGlobalRegionScheduler().run(this, t -> service.recover());   // next tick: every plugin is enabled, so the Bank check is right
    }

    /** Same {@code storage:} keys as CapitalEngine; MySQL and PostgreSQL tables get the {@code prefix} so a database can be shared. */
    private Store openStore() throws java.sql.SQLException {
        var s = getConfig().getConfigurationSection("storage");
        String type = s == null ? "sqlite" : s.getString("type", "sqlite").toLowerCase(), host = s == null ? "localhost" : s.getString("host", "localhost");
        String name = s == null ? "capitalmarket" : s.getString("database", "capitalmarket"), user = s == null ? "" : s.getString("user", ""), pass = s == null ? "" : s.getString("password", "");
        int port = s == null ? 3306 : s.getInt("port", type.startsWith("postgres") ? 5432 : 3306), pool = s == null ? 0 : s.getInt("pool-size", 0);
        String prefix = s == null ? "" : s.getString("prefix", type.equals("sqlite") ? "" : "market_");
        return switch (type) {
            case "sqlite" -> new Store(Store.Dialect.SQLITE, "jdbc:sqlite:" + new File(getDataFolder(), s == null ? "market.db" : s.getString("file", "market.db")).getAbsolutePath(), null, null, pool > 0 ? pool : 2, prefix);
            case "mysql", "mariadb" -> new Store(Store.Dialect.MYSQL, "jdbc:mysql://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=utf8", user, pass, pool > 0 ? pool : 4, prefix);
            case "postgres", "postgresql" -> new Store(Store.Dialect.POSTGRES, "jdbc:postgresql://" + host + ":" + port + "/" + name, user, pass, pool > 0 ? pool : 4, prefix);
            default -> throw new IllegalArgumentException("storage.type must be sqlite, mysql, mariadb or postgres (got '" + type + "')");
        };
    }

    /** Tests replace this to stand in for holograms, which MockBukkit cannot spawn. */
    Stalls newStalls(MarketService service, Menus menus) { return new Stalls(service, menus); }

    /** For tests and the simulator. */
    public MarketService service() { return service; }

    Menus menus() { return menus; }

    Stalls stalls() { return stalls; }

    Actions actions() { return actions; }

    @Override public void onDisable() { if (service != null) service.close(); }

    @EventHandler public void onDay(CapitalDayEvent e) { service.onDay(e.endedDay(), e.dayIndex(), e.catchUp()); }

    @EventHandler public void onWeek(CapitalWeekEvent e) { service.onWeek(e.weekIndex(), e.catchUp()); }

    /** A player answering a chat prompt ("how many?") isn't talking to the server. */
    @EventHandler(priority = EventPriority.LOWEST) public void onChat(AsyncChatEvent e) {
        if (actions.answer(e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()))) e.setCancelled(true);
    }
}
