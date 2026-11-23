package com.patelheet.capitalengine.trade;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.event.CapitalDayEvent;
import com.patelheet.capitalengine.api.event.CapitalWeekEvent;
import com.patelheet.capitalengine.api.service.IndicatorProvider;
import com.patelheet.capitalengine.trade.storage.Store;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires CapitalTrade together. The economy lives in {@link TradeService}. */
public class CapitalTradePlugin extends JavaPlugin implements Listener {
    private TradeService service;
    private Messages msg;
    private Actions actions;
    private Menus menus;
    private Ports ports;

    @Override public void onEnable() {
        CapitalApi api = getServer().getServicesManager().load(CapitalApi.class);
        if (api == null) { getLogger().severe("CapitalEngine's API isn't available."); getServer().getPluginManager().disablePlugin(this); return; }
        FileUpdater.sync(this, "config.yml");   // new settings arrive on upgrade; the owner's values are never changed
        for (String f : new String[]{"exports.yml", "imports.yml"}) if (!new File(getDataFolder(), f).exists()) saveResource(f, false);   // owner data: created once, never rewritten
        msg = new Messages(this);
        try {
            service = new TradeService(this, api, openStore(), msg, getConfig());
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not open trade.db", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadListings();
        getServer().getServicesManager().register(IndicatorProvider.class, service, this, ServicePriority.Normal);

        actions = new Actions(this, service, msg);
        menus = new Menus(actions, service, msg);
        ports = newPorts(service, menus);
        actions.ports = ports;
        ports.load();
        for (Listener l : new Listener[]{this, new Menu.Guard(), ports}) getServer().getPluginManager().registerEvents(l, this);
        var cmd = new TradeCommand(this, service, actions, menus, ports);
        getCommand("trade").setExecutor(cmd);
        getCommand("trade").setTabCompleter(cmd);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) new TradeExpansion(service).register();
        getServer().getGlobalRegionScheduler().run(this, t -> service.recover());
    }

    private void loadListings() {
        service.setListings(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "exports.yml")),
                YamlConfiguration.loadConfiguration(new File(getDataFolder(), "imports.yml")));
    }

    /** {@code /trade admin reload}: messages, exports.yml and imports.yml. Other settings need a restart. */
    void reload() {
        msg.reload();
        loadListings();
        service.recover();   // prices for newly listed items
    }

    /** Same {@code storage:} keys as CapitalEngine; MySQL and PostgreSQL tables get the {@code prefix} so a database can be shared. */
    private Store openStore() throws java.sql.SQLException {
        var s = getConfig().getConfigurationSection("storage");
        String type = s == null ? "sqlite" : s.getString("type", "sqlite").toLowerCase(), host = s == null ? "localhost" : s.getString("host", "localhost");
        String name = s == null ? "capitaltrade" : s.getString("database", "capitaltrade"), user = s == null ? "" : s.getString("user", ""), pass = s == null ? "" : s.getString("password", "");
        int port = s == null ? 3306 : s.getInt("port", type.startsWith("postgres") ? 5432 : 3306), pool = s == null ? 0 : s.getInt("pool-size", 0);
        String prefix = s == null ? "" : s.getString("prefix", type.equals("sqlite") ? "" : "trade_");
        return switch (type) {
            case "sqlite" -> new Store(Store.Dialect.SQLITE, "jdbc:sqlite:" + new File(getDataFolder(), s == null ? "trade.db" : s.getString("file", "trade.db")).getAbsolutePath(), null, null, pool > 0 ? pool : 2, prefix);
            case "mysql", "mariadb" -> new Store(Store.Dialect.MYSQL, "jdbc:mysql://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=utf8", user, pass, pool > 0 ? pool : 4, prefix);
            case "postgres", "postgresql" -> new Store(Store.Dialect.POSTGRES, "jdbc:postgresql://" + host + ":" + port + "/" + name, user, pass, pool > 0 ? pool : 4, prefix);
            default -> throw new IllegalArgumentException("storage.type must be sqlite, mysql, mariadb or postgres (got '" + type + "')");
        };
    }

    /** Tests replace this to stand in for holograms, which MockBukkit cannot spawn. */
    Ports newPorts(TradeService service, Menus menus) { return new Ports(service, menus); }

    /** For tests and the simulator. */
    public TradeService service() { return service; }

    Actions actions() { return actions; }

    Menus menus() { return menus; }

    Ports ports() { return ports; }

    @Override public void onDisable() { if (service != null) service.close(); }

    @EventHandler public void onDay(CapitalDayEvent e) { service.onDay(e.endedDay(), e.dayIndex(), e.catchUp()); }

    @EventHandler public void onWeek(CapitalWeekEvent e) { service.onWeek(e.weekIndex(), e.catchUp()); }

    /** A player answering a chat prompt ("how many?") isn't talking to the server. */
    @EventHandler(priority = EventPriority.LOWEST) public void onChat(AsyncChatEvent e) {
        if (actions.answer(e.getPlayer(), PlainTextComponentSerializer.plainText().serialize(e.message()))) e.setCancelled(true);
    }
}
