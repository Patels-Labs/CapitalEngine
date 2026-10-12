package com.patelheet.capitalengine.core.vaultcallers;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/** Stands in for a third-party plugin: the attribution resolver in the test maps this class to "PluginC". */
public final class PluginC {
    private PluginC() {}
    public static EconomyResponse deposit(Economy e, OfflinePlayer p, double amount) { return e.depositPlayer(p, amount); }
    public static EconomyResponse withdraw(Economy e, OfflinePlayer p, double amount) { return e.withdrawPlayer(p, amount); }
    public static EconomyResponse deposit(Economy e, String name, double amount) { return e.depositPlayer(name, amount); }
    public static boolean create(Economy e, String name) { return e.createPlayerAccount(name); }
}
