package com.patelheet.capitalengine.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * What a player can do, shared by the chat commands and the menus: take items out of the inventory, call the service, report
 * the result. Callers differ only in how they ask "are you sure?" ({@link Confirm}).
 * ponytail: items leave the inventory a moment before the worker writes the shipment; a crash in that instant loses them.
 */
final class Actions {
    /** Asks the player to confirm, then runs {@code yes}. */
    interface Confirm { void ask(Player p, String key, Object[] kv, Runnable yes); }

    private record Pending(long expires, Runnable run) {}

    /** Topics for {@code /trade help <topic>}; each has a "help-<topic>" entry in messages.yml. */
    static final List<String> HELP_TOPICS = List.of("start", "export", "import", "prices", "news", "cargo", "commands");

    final CapitalTradePlugin plugin;
    final TradeService svc;
    final Messages msg;
    Ports ports;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();

    /** Confirms with a Yes/No form for Bedrock players on a Floodgate server; everyone else types /trade yes. */
    final Confirm chat;

    Actions(CapitalTradePlugin plugin, TradeService svc, Messages msg) {
        this.plugin = plugin; this.svc = svc; this.msg = msg;
        this.chat = (p, key, kv, yes) -> {
            if (floodgate() && TradeForms.yesNo(p.getUniqueId(), msg.plain("menu-confirm"), msg.plain(key.replace("confirm-", "form-confirm-"), kv),
                    msg.plain("form-yes"), msg.plain("form-no"), () -> sync(p, yes), () -> { })) return;
            say(p, key, kv);
            pending.put(p.getUniqueId(), new Pending(System.currentTimeMillis() + 30_000, yes));
        };
    }

    void help(Player p, String topic) {
        if (topic.isEmpty()) { p.sendMessage(msg.get("help-overview")); p.sendMessage(msg.get("help-topics")); }
        else if (HELP_TOPICS.contains(topic)) p.sendMessage(msg.get("help-" + topic));
        else p.sendMessage(msg.get("help-unknown", "topic", topic));
        p.sendMessage(msg.get("help-typed"));
    }

    // ---- small helpers -----------------------------------------------------------------------------------------

    String coins(long n) { return svc.api.currency().format(n); }

    void say(Player p, String key, Object... kv) { p.sendMessage(msg.get("prefix").append(msg.get(key, kv))); }

    /** Back on the player's own thread (futures finish on the worker). */
    void sync(Player p, Runnable r) { p.getScheduler().run(plugin, t -> r.run(), null); }

    <T> void then(Player p, CompletableFuture<T> f, Consumer<T> done) {
        f.whenComplete((v, e) -> sync(p, () -> { if (e != null) say(p, "err-other"); else done.accept(v); }));
    }

    boolean confirmNow(Player p) {
        Pending pe = pending.remove(p.getUniqueId());
        if (pe == null || pe.expires() < System.currentTimeMillis()) return false;
        pe.run().run();
        return true;
    }

    /** True at a Port (or anywhere, if the owner allows it); otherwise says where to go. */
    boolean atPort(Player p) {
        if (ports.atPort(p)) return true;
        say(p, "need-port");
        return false;
    }

    /** The key of the exportable item in the main hand, or null (having said why). */
    String hand(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h.getType().isAir()) { say(p, "no-item"); return null; }
        String key = Items.keyOf(h);
        if (key == null) { say(p, "cant-trade"); return null; }
        if (!svc.exports.containsKey(key)) { say(p, "not-wanted", "item", Items.pretty(key)); return null; }
        return key;
    }

    // ---- inventory ---------------------------------------------------------------------------------------------

    int count(Player p, String key) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (s != null && key.equals(Items.keyOf(s))) n += s.getAmount();
        return n;
    }

    /** Takes up to {@code qty} of an item out of the inventory and returns how many it took. */
    int take(Player p, String key, int qty) {
        int left = qty;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && left > 0; i++) {
            if (inv[i] == null || !key.equals(Items.keyOf(inv[i]))) continue;
            int n = Math.min(left, inv[i].getAmount());
            if (n == inv[i].getAmount()) inv[i] = null; else inv[i].setAmount(inv[i].getAmount() - n);
            left -= n;
        }
        p.getInventory().setStorageContents(inv);
        return qty - left;
    }

    /**
     * Cargo -> inventory; whatever doesn't fit goes back into Cargo. Runs after every shipment, so items an export didn't take
     * come straight back. ponytail: Cargo is emptied a moment before the items are given; a crash in that instant loses them.
     */
    void collect(Player p, boolean quiet) {
        then(p, svc.takeCargo(p.getUniqueId()), got -> {
            Map<String, Integer> left = new HashMap<>();
            int given = 0;
            for (var e : got.entrySet()) {
                int over = p.getInventory().addItem(Items.stacks(e.getKey(), e.getValue())).values().stream().mapToInt(ItemStack::getAmount).sum();
                if (over > 0) left.put(e.getKey(), over);
                given += e.getValue() - over;
            }
            if (!left.isEmpty()) { svc.stow(p.getUniqueId(), left); say(p, "cargo-full", "qty", left.values().stream().mapToInt(Integer::intValue).sum()); }
            if (given > 0 && !quiet) say(p, "cargo-collected", "qty", given);
            else if (got.isEmpty() && !quiet) say(p, "cargo-empty");
        });
    }

    // ---- shipments ---------------------------------------------------------------------------------------------

    /** Exports {@code wanted} of an item (0 for everything in the inventory) after confirmation. */
    void startExport(Player p, String key, int wanted, Confirm c) {
        if (!atPort(p)) return;
        int have = count(p, key);
        int qty = wanted <= 0 ? have : Math.min(wanted, have);
        if (qty <= 0) { say(p, "not-enough-items", "have", have, "item", Items.pretty(key)); return; }
        then(p, svc.quoteExport(key, qty), q -> {
            if (q.qty() == 0) { say(p, "export-" + q.reason()); return; }
            if (q.qty() < qty) say(p, "export-limited-" + q.reason(), "qty", q.qty(), "wanted", qty);
            c.ask(p, "confirm-export", new Object[]{"qty", q.qty(), "item", Items.pretty(key), "total", coins(q.total())}, () -> {
                int taken = take(p, key, q.qty());
                if (taken == 0) { say(p, "not-enough-items", "have", 0, "item", Items.pretty(key)); return; }
                then(p, svc.export(p.getUniqueId(), key, taken), r -> {
                    if (r.qty() > 0) say(p, "exported", "qty", r.qty(), "item", Items.pretty(key), "total", coins(r.coins()));
                    if (r.error() != null) say(p, r.error());
                    collect(p, true);
                });
            });
        });
    }

    /** Imports {@code qty} of an item after confirmation. */
    void startImport(Player p, String key, int qty, Confirm c) {
        if (!atPort(p)) return;
        then(p, svc.quoteImport(key, qty), q -> {
            if (q.qty() == 0) { say(p, "import-" + q.reason()); return; }
            if (q.qty() < qty) say(p, "import-limited", "qty", q.qty(), "wanted", qty);
            c.ask(p, "confirm-import", new Object[]{"qty", q.qty(), "item", Items.pretty(key), "total", coins(q.total()), "tariff", coins(q.tariff())},
                    () -> then(p, svc.importItems(p.getUniqueId(), key, q.qty(), q.total()), r -> {
                        if (r.qty() > 0) say(p, "imported", "qty", r.qty(), "item", Items.pretty(key), "total", coins(r.coins()));
                        if (r.error() != null) say(p, r.error(), "total", coins(r.coins()));
                        if (r.qty() > 0) collect(p, true);
                    }));
        });
    }

    // ---- typed answers -----------------------------------------------------------------------------------------

    private boolean floodgate() { return plugin.getServer().getPluginManager().getPlugin("floodgate") != null; }

    /** Asks for a whole number: a form for Bedrock players on a Floodgate server, a chat line for everyone else. */
    void askAmount(Player p, Consumer<Integer> done) {
        p.closeInventory();
        Consumer<String> parse = s -> { int n = whole(p, s); if (n > 0) done.accept(n); };
        if (floodgate() && TradeForms.inputs(p.getUniqueId(), msg.plain("form-amount-title"), List.of(msg.plain("form-amount")), v -> sync(p, () -> parse.accept(v.get(0))), () -> { })) return;
        prompts.put(p.getUniqueId(), parse);
        say(p, "prompt-amount");
        say(p, "prompt-cancel");
    }

    /** From the chat listener: true if the line was an answer. */
    boolean answer(Player p, String text) {
        Consumer<String> c = prompts.remove(p.getUniqueId());
        if (c == null) return false;
        if (text.equalsIgnoreCase("cancel")) say(p, "prompt-cancelled"); else sync(p, () -> c.accept(text));
        return true;
    }

    int whole(Player p, String s) {
        try { int n = Integer.parseInt(s.trim()); if (n > 0 && n <= 100_000) return n; } catch (NumberFormatException ignored) { }
        say(p, "bad-number");
        return 0;
    }

    static List<String> tabItems(Map<String, ?> m, String prefix) {
        List<String> out = new ArrayList<>();
        for (String k : m.keySet()) if (k.startsWith(prefix.toLowerCase())) out.add(k);
        return out;
    }
}
