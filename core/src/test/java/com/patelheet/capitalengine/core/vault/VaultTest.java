package com.patelheet.capitalengine.core.vault;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.AccountKind;
import com.patelheet.capitalengine.core.PluginTestBaseAccess;
import com.patelheet.capitalengine.core.policy.Attribution;
import com.patelheet.capitalengine.core.vaultcallers.PluginA;
import com.patelheet.capitalengine.core.vaultcallers.PluginB;
import com.patelheet.capitalengine.core.vaultcallers.PluginC;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Both Vault flavours on top of the real plugin: attribution, each vault-mode, external accounts, vault2 amounts. */
class VaultTest extends PluginTestBaseAccess {
    Economy eco;
    OfflinePlayer sam;

    @BeforeEach void adapters() {
        var ops = new VaultOps(core.ledger(), core.settings().currency(), core.activity(), core.messages());
        Attribution attr = new Attribution(cls -> {
            String n = cls.getSimpleName();
            return n.startsWith("Plugin") && cls.getPackageName().endsWith("vaultcallers") ? Optional.of(n) : Optional.empty();
        });
        eco = new LegacyVaultEconomy(ops, attr);
        core.setPolicy("PluginA", "vault-mode", "issue");
        core.setPolicy("PluginB", "vault-mode", "treasury");
        core.setPolicy("PluginC", "vault-mode", "deny");
        sam = Bukkit.getOfflinePlayer(UUID.randomUUID());
    }

    long supply() { return api.stats().moneySupply(); }

    @Test void bothProvidersAreRegisteredWhenVaultIsPresent() {
        var legacy = server.getServicesManager().getRegistration(Economy.class);
        assertNotNull(legacy);
        assertEquals("CapitalEngine", legacy.getProvider().getName());
        assertFalse(legacy.getProvider().hasBankSupport());
        var v2 = server.getServicesManager().getRegistration(net.milkbowl.vault2.economy.Economy.class);
        assertNotNull(v2);
        assertEquals("CapitalEngine", v2.getProvider().getName());
        assertFalse(v2.getProvider().hasSharedAccountSupport());
        assertFalse(v2.getProvider().hasMultiCurrencySupport());
    }

    @Test void issueModeCreatesAndDestroysMoneyAndIsAttributedToTheCaller() {
        assertEquals(ResponseType.SUCCESS, PluginA.deposit(eco, sam, 50).type);
        assertEquals(50.0, eco.getBalance(sam));
        assertEquals(5000, supply());
        assertEquals(5000, api.stats().flowsToday().get("PluginA").issued());
        assertEquals(ResponseType.SUCCESS, PluginA.withdraw(eco, sam, 20).type);
        assertEquals(30.0, eco.getBalance(sam));
        assertEquals(3000, supply());
    }

    @Test void treasuryModePaysFromTheTreasuryFirst() throws Exception {
        PluginA.deposit(eco, sam, 100);
        await(api.collect(other, api.accounts().player(((org.bukkit.OfflinePlayer) sam).getUniqueId()), 3000, "t.fee", "fee"));
        long before = supply();
        OfflinePlayer mia = Bukkit.getOfflinePlayer(UUID.randomUUID());
        PluginB.deposit(eco, mia, 20);   // 20 coins out of the 30 in the Treasury
        assertEquals(before, supply(), "recycled money, nothing created");
        assertEquals(1000, api.treasury().balance());
        PluginB.deposit(eco, mia, 50);   // 10 from the Treasury, 40 created
        assertEquals(before + 4000, supply());
        PluginB.withdraw(eco, mia, 5);   // goes back to the Treasury, not destroyed
        assertEquals(before + 4000, supply());
        assertEquals(500, api.treasury().balance());
    }

    @Test void denyModeRefusesBothWays() {
        var r = PluginC.deposit(eco, sam, 10);
        assertEquals(ResponseType.FAILURE, r.type);
        assertTrue(r.errorMessage.contains("not allowed to create money"));
        assertEquals(0.0, eco.getBalance(sam));
        assertEquals(ResponseType.FAILURE, PluginC.withdraw(eco, sam, 1).type);
        assertEquals(0, supply());
    }

    @Test void aShopThatWithdrawsAndDepositsNetsToZero() {
        OfflinePlayer buyer = Bukkit.getOfflinePlayer(UUID.randomUUID()), seller = Bukkit.getOfflinePlayer(UUID.randomUUID());
        PluginA.deposit(eco, buyer, 100);
        long before = supply();
        PluginA.withdraw(eco, buyer, 40);
        PluginA.deposit(eco, seller, 40);
        assertEquals(before - 4000 + 4000, supply());
        var flow = api.stats().flowsToday().get("PluginA");
        assertEquals(flow.issued() - flow.destroyed(), supply(), "net flow equals what exists");
        assertEquals(60.0, eco.getBalance(buyer));
        assertEquals(40.0, eco.getBalance(seller));
    }

    @Test void failedOrOddAmountsChangeNothing() {
        PluginA.deposit(eco, sam, 10);
        assertEquals(ResponseType.FAILURE, PluginA.withdraw(eco, sam, 10.01).type, "more than you have");
        assertEquals(ResponseType.FAILURE, PluginA.deposit(eco, sam, -1).type);
        assertEquals(ResponseType.FAILURE, PluginA.deposit(eco, sam, Double.NaN).type);
        assertEquals(ResponseType.FAILURE, PluginA.deposit(eco, sam, Double.POSITIVE_INFINITY).type);
        assertEquals(ResponseType.SUCCESS, PluginA.deposit(eco, sam, 0).type, "zero is a harmless no-op");
        assertEquals(10.0, eco.getBalance(sam));
        assertFalse(eco.has(sam, 10.01));
        assertTrue(eco.has(sam, 10.0));
    }

    @Test void legacyDoublesRoundHalfEvenToTheSmallestUnit() {
        PluginA.deposit(eco, sam, 0.125);   // 12.5 units -> 12
        assertEquals(0.12, eco.getBalance(sam), 1e-9);
        PluginA.deposit(eco, sam, 0.135);   // 13.5 units -> 14
        assertEquals(0.26, eco.getBalance(sam), 1e-9);
    }

    @Test void fakePlayerNamesGetExternalAccountsOwnedByTheCaller() {
        assertTrue(PluginA.create(eco, "town-Foo"));
        assertFalse(PluginA.create(eco, "town-Foo"), "already exists");
        assertTrue(eco.hasAccount("town-foo"));
        assertEquals(ResponseType.SUCCESS, PluginA.deposit(eco, "town-Foo", 25).type);
        assertEquals(25.0, eco.getBalance("TOWN-foo"));
        var acct = core.ledger().externalByName("town-foo");
        assertEquals(AccountKind.EXTERNAL, acct.kind());
        assertEquals(Optional.of("plugina"), acct.ownerPlugin());
        assertEquals(ResponseType.FAILURE, PluginA.deposit(eco, "nobody-here", 1).type, "no account, no deposit");
        assertEquals(0.0, eco.getBalance("nobody-here"));
    }

    @Test void vault2UsesExactDecimalsAndRejectsExtraPrecision() {
        var v2 = server.getServicesManager().getRegistration(net.milkbowl.vault2.economy.Economy.class).getProvider();
        UUID id = UUID.randomUUID();
        assertTrue(v2.createAccount(id, "Sam", true));
        assertFalse(v2.createAccount(id, "Sam", true));
        assertTrue(v2.deposit("PluginA", id, new BigDecimal("12.34")).transactionSuccess());
        assertEquals(new BigDecimal("12.34"), v2.getBalance("PluginA", id));
        assertFalse(v2.deposit("PluginA", id, new BigDecimal("0.001")).transactionSuccess(), "more precision than 2 decimals");
        assertFalse(v2.withdraw("PluginA", id, new BigDecimal("12.35")).transactionSuccess());
        assertTrue(v2.withdraw("PluginA", id, new BigDecimal("2.34")).transactionSuccess());
        assertTrue(v2.has("PluginA", id, new BigDecimal("10")));
        assertEquals(1000, core.ledger().byId(id).balance());
        assertEquals(1234, api.stats().flowsToday().get("PluginA").issued(), "attributed by the pluginName argument");
        assertFalse(v2.deposit("PluginC", id, new BigDecimal("1")).transactionSuccess(), "deny applies to vault2 too");
        UUID town = UUID.randomUUID();
        assertTrue(v2.createAccount(town, "Town", false));
        assertEquals(AccountKind.EXTERNAL, core.ledger().byId(town).kind());
        assertFalse(v2.deposit("PluginA", UUID.randomUUID(), BigDecimal.ONE).transactionSuccess(), "unknown non-player UUID");
    }
}
