package com.patelheet.capitalengine.core.vault;

import com.patelheet.capitalengine.core.policy.Attribution;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

/** Registers CapitalEngine as the economy provider: the legacy interface always, vault2 only if VaultUnlocked is there. */
public final class VaultHook {
    private VaultHook() {}

    public static void register(Plugin core, VaultOps ops, Attribution attribution, Logger log) {
        var pm = Bukkit.getPluginManager();
        if (pm.getPlugin("Vault") != null || pm.getPlugin("VaultUnlocked") != null) {   // VaultUnlocked also answers to "Vault"
            Bukkit.getServicesManager().register(Economy.class, new LegacyVaultEconomy(ops, attribution), core, ServicePriority.Highest);
            log.info("Registered with Vault.");
        }
        if (classExists("net.milkbowl.vault2.economy.Economy")) {
            Vault2Economy.register(core, ops);
            log.info("Registered with VaultUnlocked (exact amounts).");
        }
    }

    private static boolean classExists(String name) {
        try { Class.forName(name, false, VaultHook.class.getClassLoader()); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}
