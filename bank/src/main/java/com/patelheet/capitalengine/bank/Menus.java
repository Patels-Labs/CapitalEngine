package com.patelheet.capitalengine.bank;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The bank's chest menus: Bank, Savings, Loan, Land upkeep and a Yes/No screen. Every button says what it does in its name
 * (Bedrock players may not see lore), and anything that costs money goes through the Yes/No screen first.
 */
final class Menus {
    static final int BACK = 18, HELP = 26, DEPOSIT = 11, WITHDRAW = 20, YES = 11, NO = 15;
    private final Actions a;
    private final BankService svc;
    private final Messages msg;

    /** Confirms with a Yes/No menu. */
    final Actions.Confirm screen = this::confirm;

    Menus(Actions a, BankService svc, Messages msg) { this.a = a; this.svc = svc; this.msg = msg; }

    private Menu menu(String titleKey) { return new Menu(msg.get(titleKey), 3); }

    private Menu withNav(Menu m, Player p) {
        m.set(BACK, Menu.icon(Material.ARROW, msg.get("btn-back")), () -> main(p));
        return m.set(HELP, Menu.icon(Material.BOOK, msg.get("btn-help")), () -> { p.closeInventory(); a.help(p, ""); });
    }

    void main(Player p) {
        UUID id = p.getUniqueId();
        a.then(p, svc.loan(id), loan -> {
            Menu m = menu("menu-bank");
            m.set(11, Menu.icon(Material.GOLD_INGOT, msg.get("btn-savings", "savings", a.coins(svc.savings(id))), msg.get("lore-savings", "rate", rate())), () -> savings(p));
            m.set(13, Menu.icon(Material.EMERALD, loan.isPresent() ? msg.get("btn-loan-left", "left", a.coins(loan.get().left())) : msg.get("btn-loan"), msg.get("lore-loan")), () -> loan(p));
            long due = svc.upkeepDue.getOrDefault(id, 0L);
            m.set(15, Menu.icon(Material.GRASS_BLOCK, due > 0 ? msg.get("btn-upkeep-owed", "amount", a.coins(due)) : msg.get("btn-upkeep"), msg.get("lore-upkeep")), () -> upkeep(p));
            m.set(HELP, Menu.icon(Material.BOOK, msg.get("btn-help")), () -> { p.closeInventory(); a.help(p, ""); });
            m.open(p);
        });
    }

    private String rate() { return Actions.pct(svc.savingsPerDay() * 7); }

    void savings(Player p) {
        UUID id = p.getUniqueId();
        Menu m = withNav(menu("menu-savings"), p);
        m.set(4, Menu.icon(Material.GOLD_BLOCK, msg.get("btn-savings-info", "savings", a.coins(svc.savings(id)), "rate", rate()), msg.get("lore-savings-info")));
        // row 2: deposit 10 / 100 / 1,000 / All / other amount; row 3 the same for withdrawing
        String[] amounts = {"10", "100", "1000"};
        for (int i = 0; i < 3; i++) {
            long n = a.coinsOf(amounts[i]);
            m.set(DEPOSIT + i, Menu.icon(Material.LIME_DYE, msg.get("btn-deposit", "amount", a.coins(n))), () -> a.deposit(p, n, () -> savings(p)));
            m.set(WITHDRAW + i, Menu.icon(Material.ORANGE_DYE, msg.get("btn-withdraw", "amount", a.coins(n))), () -> a.withdraw(p, n, () -> savings(p)));
        }
        m.set(DEPOSIT + 3, Menu.icon(Material.CHEST, msg.get("btn-deposit-all")), () -> a.deposit(p, 0, () -> savings(p)));
        m.set(WITHDRAW + 3, Menu.icon(Material.ENDER_CHEST, msg.get("btn-withdraw-all")), () -> a.withdraw(p, 0, () -> savings(p)));
        m.set(DEPOSIT + 4, Menu.icon(Material.HOPPER, msg.get("btn-deposit-other")), () -> a.askAmount(p, "btn-deposit-other", n -> a.deposit(p, n, () -> { })));
        m.set(WITHDRAW + 4, Menu.icon(Material.DROPPER, msg.get("btn-withdraw-other")), () -> a.askAmount(p, "btn-withdraw-other", n -> a.withdraw(p, n, () -> { })));
        m.open(p);
    }

    void loan(Player p) {
        UUID id = p.getUniqueId();
        a.then(p, svc.loan(id), loan -> {
            if (loan.isPresent()) {
                var l = loan.get();
                Menu m = withNav(menu("menu-loan"), p);
                m.set(4, Menu.icon(Material.PAPER, msg.get("btn-loan-info", "left", a.coins(l.left()), "instalment", a.coins(l.instalment())),
                        l.arrears() > 0 ? msg.get("lore-loan-behind", "amount", a.coins(l.arrears())) : msg.get("lore-loan-ok")));
                if (l.arrears() > 0) a.say(p, "loan-behind", "amount", a.coins(l.arrears()));   // in chat too: Bedrock may not show lore
                m.set(11, Menu.icon(Material.EMERALD_BLOCK, msg.get("btn-repay-all")), () -> a.startRepay(p, 0, screen));
                m.set(15, Menu.icon(Material.EMERALD, msg.get("btn-repay-other")), () -> a.askAmount(p, "btn-repay-other", n -> a.startRepay(p, n, a.chat)));
                m.open(p);
                return;
            }
            a.then(p, svc.offer(id, CapitalBankPlugin.playedMinutes(p)), o -> {
                Menu m = withNav(menu("menu-loan"), p);
                if (o.reason() != null) {
                    m.set(13, Menu.icon(Material.BARRIER, msg.get("loan-" + o.reason(), "hours", svc.minPlayMinutes / 60)));   // the reason is the name, so Bedrock shows it
                    m.open(p);
                    return;
                }
                m.set(4, Menu.icon(Material.PAPER, msg.get("btn-loan-offer", "limit", a.coins(o.limit()), "rate", Actions.pct(o.weeklyRate()), "days", o.termDays())));
                long[] amounts = {o.limit() / 4, o.limit() / 2, o.limit()};
                for (int i = 0; i < 3; i++) {
                    long n = amounts[i] / 100 * 100;   // whole coins
                    if (n <= 0) continue;
                    m.set(11 + i, Menu.icon(Material.GOLD_NUGGET, msg.get("btn-borrow", "amount", a.coins(n))), () -> a.startBorrow(p, n, screen));
                }
                m.set(15, Menu.icon(Material.NAME_TAG, msg.get("btn-borrow-other")), () -> a.askAmount(p, "btn-borrow-other", n -> a.startBorrow(p, n, a.chat)));
                m.open(p);
            });
        });
    }

    void upkeep(Player p) {
        UUID id = p.getUniqueId();
        a.then(p, svc.upkeep(id, a.plugin.areaOf(id)), u -> {
            Menu m = withNav(menu("menu-upkeep"), p);
            m.set(10, Menu.icon(Material.GRASS_BLOCK, msg.get("btn-upkeep-area", "area", u.area())));
            m.set(12, Menu.icon(Material.OAK_SAPLING, msg.get("btn-upkeep-free", "free", u.free())));
            m.set(14, Menu.icon(Material.CLOCK, msg.get("btn-upkeep-daily", "amount", a.coins(u.daily()))));
            m.set(16, u.due() > 0 ? Menu.icon(u.frozen() ? Material.BLUE_ICE : Material.YELLOW_WOOL, msg.get(u.frozen() ? "btn-upkeep-frozen" : "btn-upkeep-due", "amount", a.coins(u.due())))
                    : Menu.icon(Material.LIME_WOOL, msg.get("btn-upkeep-paid")));
            m.open(p);
        });
    }

    private void confirm(Player p, String key, Object[] kv, Runnable yes) {
        Menu m = menu("menu-confirm");
        m.set(13, Menu.icon(Material.PAPER, msg.get("form-" + key, kv)));
        m.set(YES, Menu.icon(Material.LIME_WOOL, msg.get("btn-yes")), () -> { p.closeInventory(); yes.run(); });
        m.set(NO, Menu.icon(Material.RED_WOOL, msg.get("btn-no")), () -> { p.closeInventory(); a.say(p, "cancelled"); });
        m.open(p);
    }
}
