package com.patelheet.capitalengine.bank;

import com.patelheet.capitalengine.bank.BankService.Result;
import com.patelheet.capitalengine.bank.math.BankMath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/**
 * What a player can do, shared by the chat commands and the menus: call the service, report the result. Callers differ only in
 * how they ask "are you sure?" ({@link Confirm}): a chat line answered with {@code /bank yes}, a Floodgate form, or a Yes/No menu.
 */
final class Actions {
    /** Asks the player to confirm a cost, then runs {@code yes}. */
    interface Confirm { void ask(Player p, String key, Object[] kv, Runnable yes); }

    private record Pending(long expires, Runnable run) {}

    /** Topics for {@code /bank help <topic>}; each has a "help-<topic>" entry in messages.yml. */
    static final List<String> HELP_TOPICS = List.of("start", "savings", "loans", "upkeep", "commands");

    final CapitalBankPlugin plugin;
    final BankService svc;
    final Messages msg;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();

    /** Confirms with a Yes/No form for Bedrock players on a Floodgate server; everyone else types /bank yes. */
    final Confirm chat;

    Actions(CapitalBankPlugin plugin, BankService svc, Messages msg) {
        this.plugin = plugin; this.svc = svc; this.msg = msg;
        this.chat = (p, key, kv, yes) -> {
            if (floodgate() && BankForms.yesNo(p.getUniqueId(), msg.plain("menu-confirm"), msg.plain("form-" + key, kv),
                    msg.plain("form-yes"), msg.plain("form-no"), () -> sync(p, yes), () -> { })) return;
            say(p, key, kv);
            pending.put(p.getUniqueId(), new Pending(System.currentTimeMillis() + 30_000, yes));
        };
    }

    // ---- small helpers -----------------------------------------------------------------------------------------

    String coins(long n) { return svc.coins(n); }

    long coinsOf(String text) { return svc.api.currency().parse(text); }

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

    /** A typed amount of coins ("all" is handled by the caller), or 0 after saying it isn't one. */
    long amount(Player p, String s) {
        try { long n = coinsOf(s.trim()); if (n > 0) return n; } catch (IllegalArgumentException ignored) { }
        say(p, "bad-amount");
        return 0;
    }

    /** Prints the help overview, or one topic. Every topic is also a clickable button, with a typed fallback for Bedrock. */
    void help(Player p, String topic) {
        if (topic.isEmpty()) {
            p.sendMessage(msg.get("help-overview"));
            p.sendMessage(msg.get("help-topics"));
        } else if (HELP_TOPICS.contains(topic)) p.sendMessage(msg.get("help-" + topic));
        else p.sendMessage(msg.get("help-unknown", "topic", topic));
        p.sendMessage(msg.get("help-typed"));
    }

    // ---- savings -----------------------------------------------------------------------------------------------

    /** {@code amount} ≤ 0 means everything in the wallet. No confirmation: the coins stay the player's and can be taken out any time. */
    void deposit(Player p, long amount, Runnable after) {
        then(p, svc.deposit(p.getUniqueId(), amount), r -> { report(p, r, "deposited"); after.run(); });
    }

    void withdraw(Player p, long amount, Runnable after) {
        then(p, svc.withdraw(p.getUniqueId(), amount), r -> { report(p, r, "withdrew"); after.run(); });
    }

    private void report(Player p, Result r, String okKey) {
        if (r.error() != null) say(p, r.error());
        else say(p, okKey, "amount", coins(r.amount()), "savings", coins(svc.savings(p.getUniqueId())));
    }

    // ---- loans -------------------------------------------------------------------------------------------------

    /** Shows what the player may borrow (or why not), or their loan. */
    void loanInfo(Player p) {
        UUID id = p.getUniqueId();
        then(p, svc.loan(id), l -> {
            if (l.isPresent()) {
                var x = l.get();
                say(p, "loan-status", "left", coins(x.left()), "instalment", coins(x.instalment()));
                if (x.arrears() > 0) say(p, "loan-behind", "amount", coins(x.arrears()));
                return;
            }
            then(p, svc.offer(id, CapitalBankPlugin.playedMinutes(p)), o -> {
                if (o.reason() != null) say(p, "loan-" + o.reason(), "hours", svc.minPlayMinutes / 60);
                else say(p, "loan-offer", "limit", coins(o.limit()), "rate", pct(o.weeklyRate()), "days", o.termDays());
            });
        });
    }

    static String pct(double weekly) { return String.format(java.util.Locale.ROOT, "%.1f%%", weekly * 100).replace(".0%", "%"); }

    void startBorrow(Player p, long amount, Confirm c) {
        UUID id = p.getUniqueId();
        long played = CapitalBankPlugin.playedMinutes(p);
        then(p, svc.offer(id, played), o -> {
            if (o.reason() != null) { say(p, "loan-" + o.reason(), "hours", svc.minPlayMinutes / 60); return; }
            if (amount > o.limit()) { say(p, "loan-over-limit", "limit", coins(o.limit())); return; }
            BankMath.Terms t = svc.preview(amount);
            c.ask(p, "confirm-loan", new Object[]{"amount", coins(amount), "instalment", coins(t.instalment()), "days", t.days(), "total", coins(t.total())},
                    () -> then(p, svc.borrow(id, amount, played), r -> {
                        if (r.error() != null) say(p, r.error(), "limit", coins(o.limit()), "hours", svc.minPlayMinutes / 60);
                        else say(p, "borrowed", "amount", coins(amount), "instalment", coins(r.terms().instalment()), "days", r.terms().days());
                    }));
        });
    }

    /** {@code amount} ≤ 0 means everything left (or as much as the wallet holds). */
    void startRepay(Player p, long amount, Confirm c) {
        UUID id = p.getUniqueId();
        then(p, svc.loan(id), l -> {
            if (l.isEmpty()) { say(p, "no-loan"); return; }
            long wallet = svc.wallet(id).balance(), left = l.get().left();
            long amt = amount > 0 ? Math.min(amount, left) : Math.min(left, wallet);
            if (amt <= 0 || amt > wallet) { say(p, "err-funds"); return; }
            c.ask(p, "confirm-repay", new Object[]{"amount", coins(amt), "left", coins(left - amt)}, () -> then(p, svc.repay(id, amt), r -> {
                if (r.error() != null) say(p, r.error());
                else if (r.amount() == left) say(p, "repaid-all", "amount", coins(r.amount()));
                else say(p, "repaid", "amount", coins(r.amount()), "left", coins(left - r.amount()));
            }));
        });
    }

    // ---- asking for typed answers ------------------------------------------------------------------------------

    private boolean floodgate() { return plugin.getServer().getPluginManager().getPlugin("floodgate") != null; }

    /** Asks for an amount of coins. Bedrock players on a Floodgate server get a form; everyone else types it in chat. */
    void askAmount(Player p, String titleKey, Consumer<Long> done) {
        p.closeInventory();
        Consumer<String> parse = s -> { long n = amount(p, s); if (n > 0) done.accept(n); };
        if (floodgate() && BankForms.inputs(p.getUniqueId(), msg.plain(titleKey), List.of(msg.plain("form-amount")), v -> sync(p, () -> parse.accept(v.get(0))), () -> { })) return;
        prompts.put(p.getUniqueId(), parse);
        say(p, "prompt-amount");
        say(p, "prompt-cancel");
    }

    /** From the chat listener: true if the line was an answer. Typing "cancel" stops. */
    boolean answer(Player p, String text) {
        Consumer<String> c = prompts.remove(p.getUniqueId());
        if (c == null) return false;
        if (text.equalsIgnoreCase("cancel")) say(p, "prompt-cancelled"); else sync(p, () -> c.accept(text));
        return true;
    }
}
