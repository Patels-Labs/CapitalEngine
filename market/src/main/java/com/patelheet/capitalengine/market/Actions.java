package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.market.MarketService.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * What a player can do, shared by the chat commands and the menus: take items out of the inventory, call the service,
 * report the result. Callers differ only in how they ask "are you sure?" ({@link Confirm}).
 * ponytail: items leave the inventory before the Stash write is queued; a crash in that instant loses them.
 */
final class Actions {
    /** Asks the player to confirm a cost, then runs {@code yes}. */
    interface Confirm { void ask(Player p, String key, Object[] kv, Runnable yes); }

    private record Pending(long expires, Runnable run) {}

    final CapitalMarketPlugin plugin;
    final MarketService svc;
    final Messages msg;
    Stalls stalls;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();

    /** Confirms with a Yes/No form for Bedrock players on a Floodgate server; everyone else types /market yes. */
    final Confirm chat;

    Actions(CapitalMarketPlugin plugin, MarketService svc, Messages msg) { this.plugin = plugin; this.svc = svc; this.msg = msg;
        this.chat = (p, key, kv, yes) -> {
            if (floodgate() && MarketForms.yesNo(p.getUniqueId(), msg.plain("menu-confirm"), msg.plain(key.replace("confirm-", "form-confirm-"), kv),
                    msg.plain("form-yes"), msg.plain("form-no"), () -> sync(p, yes), () -> { })) return;
            say(p, key, kv);
            pending.put(p.getUniqueId(), new Pending(System.currentTimeMillis() + 30_000, yes));
        };
    }

    /** Topics for {@code /market help <topic>}; each has a "help-<topic>" entry in messages.yml. */
    static final List<String> HELP_TOPICS = List.of("start", "sell", "buy", "prices", "orders", "stash", "stalls", "jobs", "commands");

    /** Prints the help overview, or one topic. Every topic is also a clickable button, with a typed fallback for Bedrock. */
    void help(Player p, String topic) {
        if (topic.isEmpty()) {
            p.sendMessage(msg.get("help-overview"));
            p.sendMessage(msg.get("help-topics"));
            p.sendMessage(msg.get("help-typed"));
        } else if (HELP_TOPICS.contains(topic)) {
            p.sendMessage(msg.get("help-" + topic));
            p.sendMessage(msg.get("help-typed"));
        } else {
            p.sendMessage(msg.get("help-unknown", "topic", topic));
            p.sendMessage(msg.get("help-typed"));
        }
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

    /** The key of the tradable item in the main hand, or null (having said why). */
    String hand(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h.getType().isAir()) { say(p, "no-item"); return null; }
        String key = Items.keyOf(h);
        if (key == null || svc.blocked.contains(key) || svc.blocked.contains(Items.key(h.getType()))) { say(p, "cant-trade"); return null; }
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

    /** Gives items; returns how many didn't fit. */
    int give(Player p, String key, int qty) {
        return p.getInventory().addItem(Items.stacks(key, qty)).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    /** Inventory -> Stash, then {@code next}. Gives the items back if the Stash is full. */
    void deposit(Player p, String key, int qty, Runnable next) {
        int got = take(p, key, qty);
        if (got == 0) { next.run(); return; }
        then(p, svc.deposit(p.getUniqueId(), key, got), ok -> {
            if (!ok) { give(p, key, got); say(p, "stash-full"); } else next.run();
        });
    }

    /** Stash -> inventory, at a stall unless the owner allows it anywhere. */
    void withdraw(Player p, String key, int qty, Runnable after) {
        if (!stalls.canWithdraw(p)) { say(p, "need-stall"); return; }
        then(p, svc.withdraw(p.getUniqueId(), key, qty), n -> {
            if (n == 0) { say(p, "nothing-taken"); return; }
            int left = give(p, key, n);
            if (left > 0) svc.deposit(p.getUniqueId(), key, left);   // inventory was full: put the rest back
            say(p, "taken", "qty", n - left, "item", Items.pretty(key));
            after.run();
        });
    }

    // ---- trading -----------------------------------------------------------------------------------------------

    void report(Player p, Result r, String okKey, String item, int wanted) {
        if (r.qty() > 0) say(p, okKey, "qty", r.qty(), "item", Items.pretty(item), "total", coins(r.coins()));
        if (r.error() != null) say(p, r.qty() > 0 ? "partly" : r.error(), "qty", r.qty(), "wanted", wanted, "reason", msg.plain(r.error()), "total", coins(r.coins()), "max", "");
    }

    /** Quick Sell: {@code wanted} of an item (0 for everything) from the inventory and the Stash. */
    void startSell(Player p, String key, int wanted, Confirm c) {
        then(p, svc.free(p.getUniqueId(), key), free -> {
            int avail = count(p, key) + free;
            int qty = wanted <= 0 ? avail : Math.min(wanted, avail);
            if (qty <= 0) { say(p, "not-enough-items", "have", avail, "item", Items.pretty(key)); return; }
            then(p, svc.quote(p.getUniqueId(), key, qty, false), q -> {
                if (q.qty() == 0) { say(p, "sell-" + q.reason()); return; }
                if (q.qty() < qty) say(p, "sell-limited-" + q.reason(), "qty", q.qty(), "wanted", qty);
                c.ask(p, "confirm-sell", new Object[]{"qty", q.qty(), "item", Items.pretty(key), "total", coins(q.total())}, () -> {
                    int fromInv = Math.min(count(p, key), q.qty());
                    Runnable go = () -> then(p, svc.quickSell(p.getUniqueId(), key, q.qty()), r -> report(p, r, "sold", key, q.qty()));
                    if (fromInv > 0) deposit(p, key, fromInv, go); else go.run();
                });
            });
        });
    }

    void startBuy(Player p, String key, int qty, Confirm c) {
        then(p, svc.quote(p.getUniqueId(), key, qty, true), q -> {
            if (q.qty() == 0) { say(p, "buy-no-stock"); return; }
            if (q.qty() < qty) say(p, "buy-limited", "qty", q.qty(), "wanted", qty);
            c.ask(p, "confirm-buy", new Object[]{"qty", q.qty(), "item", Items.pretty(key), "total", coins(q.total())},
                    () -> then(p, svc.quickBuy(p.getUniqueId(), key, q.qty(), q.total()), r -> report(p, r, "bought", key, q.qty())));
        });
    }

    void list(Player p, String key, int qty, long unit) {
        deposit(p, key, Math.min(qty, count(p, key)), () -> then(p, svc.list(p.getUniqueId(), key, qty, unit), r -> {
            if (r.error() != null) { say(p, r.error()); return; }
            if (r.qty() > 0) say(p, "sold", "qty", r.qty(), "item", Items.pretty(key), "total", coins(r.coins()));
            if (r.orderId() > 0) say(p, "listed", "qty", qty - r.qty(), "item", Items.pretty(key), "price", coins(unit), "id", r.orderId());
        }));
    }

    void startWanted(Player p, String key, int qty, long unit, Confirm c) {
        c.ask(p, "confirm-wanted", new Object[]{"qty", qty, "item", Items.pretty(key), "price", coins(unit), "total", coins(qty * unit)},
                () -> then(p, svc.wanted(p.getUniqueId(), key, qty, unit), r -> {
                    if (r.qty() > 0) say(p, "bought", "qty", r.qty(), "item", Items.pretty(key), "total", coins(r.coins()));
                    if (r.error() != null) say(p, r.error());
                    else if (r.orderId() > 0) say(p, "wanted", "qty", qty - r.qty(), "item", Items.pretty(key), "price", coins(unit), "id", r.orderId());
                }));
    }

    /** Jobs: put matching items in the Stash first, then deliver from it. */
    void deliver(Player p, long jobId, String key, int qty, Runnable after) {
        deposit(p, key, Math.min(qty, count(p, key)), () -> then(p, svc.jobs.deliver(p.getUniqueId(), jobId, qty), r -> {
            say(p, r.error() != null ? r.error() : "job-done", "qty", r.qty(), "item", Items.pretty(key), "total", coins(r.coins()));
            after.run();
        }));
    }

    // ---- asking for typed answers ------------------------------------------------------------------------------

    private boolean floodgate() { return plugin.getServer().getPluginManager().getPlugin("floodgate") != null; }

    /**
     * Asks for several typed answers ("qty", "price", "pay", "pay-build", "note"). Bedrock players on a Floodgate server get one
     * form; everyone else is asked one chat line at a time ("prompt-<field>"). {@code done} runs on the player's thread.
     */
    void ask(Player p, String titleKey, List<String> fields, Consumer<List<String>> done) {
        p.closeInventory();
        if (floodgate() && MarketForms.inputs(p.getUniqueId(), msg.plain(titleKey), fields.stream().map(f -> msg.plain("form-" + f)).toList(), v -> sync(p, () -> done.accept(v)), () -> { })) return;
        chain(p, fields, new ArrayList<>(), done);
    }

    private void chain(Player p, List<String> fields, List<String> got, Consumer<List<String>> done) {
        if (got.size() == fields.size()) { done.accept(got); return; }
        prompt(p, "prompt-" + fields.get(got.size()), s -> { got.add(s); chain(p, fields, got, done); });
    }

    // ---- chat prompts ------------------------------------------------------------------------------------------

    /** Asks for a typed answer; the next chat line from this player is it. Typing "cancel" stops. */
    void prompt(Player p, String key, Consumer<String> answer) {
        p.closeInventory();
        prompts.put(p.getUniqueId(), answer);
        say(p, key);
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
        try { int n = Integer.parseInt(s.trim()); if (n > 0 && n <= 1_000_000) return n; } catch (NumberFormatException ignored) { }
        say(p, "bad-number");
        return 0;
    }

    long amount(Player p, String s) {
        try { long n = svc.api.currency().parse(s.trim()); if (n > 0) return n; } catch (IllegalArgumentException ignored) { }
        say(p, "bad-price");
        return 0;
    }
}
