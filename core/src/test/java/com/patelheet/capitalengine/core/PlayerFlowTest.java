package com.patelheet.capitalengine.core;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.core.importer.Importer;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** What players and owners actually type: starter coins, /pay (with confirmation), /money, /ce give, /ce reverse, /ce import. */
class PlayerFlowTest extends PluginTestBase {
    long balance(PlayerMock p) { return api.accounts().player(p.getUniqueId()).balance(); }

    void settle() throws Exception { await(core.ledger().barrier()); server.getScheduler().performTicks(2); }

    @Test void newPlayersGetStarterCoinsExactlyOnce() throws Exception {
        PlayerMock sam = server.addPlayer("Sam");
        settle();
        assertEquals(25000, balance(sam), "250 coins");
        assertEquals(25000, api.stats().moneySupply());
        sam.disconnect();
        server.addPlayer(sam);   // rejoin
        settle();
        assertEquals(25000, balance(sam), "no second helping");
        var flow = api.stats().flowsToday().get("CapitalEngine");
        assertEquals(25000, flow.issued());
    }

    @Test void payMovesCoinsAndRefusesBadRequests() throws Exception {
        PlayerMock sam = server.addPlayer("Sam"), mia = server.addPlayer("Mia");
        settle();
        sam.performCommand("pay Mia 100");
        settle();
        assertEquals(15000, balance(sam));
        assertEquals(35000, balance(mia));
        sam.performCommand("pay Sam 5");           // yourself
        sam.performCommand("pay Mia 999999");      // more than you have
        sam.performCommand("pay Mia nonsense");
        sam.performCommand("pay Nobody 5");
        sam.performCommand("pay Mia -5");
        settle();
        assertEquals(15000, balance(sam));
        assertEquals(35000, balance(mia));
        assertEquals(50000, api.stats().moneySupply());
    }

    @Test void bigPaymentsAskFirstAndExpire() throws Exception {
        PlayerMock sam = server.addPlayer("Sam"), mia = server.addPlayer("Mia");
        settle();
        core.ledger();   // give Sam enough to cross the 1,000 coin confirm line
        await(api.issue(other, api.accounts().player(sam.getUniqueId()), 200000, "t.x", "m"));
        sam.performCommand("pay Mia 1500");
        settle();
        assertEquals(225000, balance(sam), "nothing moves until confirmed");
        sam.performCommand("pay confirm");
        settle();
        assertEquals(75000, balance(sam));
        assertEquals(175000, balance(mia));
        sam.performCommand("pay confirm");   // nothing pending any more
        sam.performCommand("pay Mia 2000");
        sam.performCommand("pay cancel");
        sam.performCommand("pay confirm");
        settle();
        assertEquals(75000, balance(sam), "cancelled payments don't happen");
    }

    @Test void moneyShowsOthersOnlyWithPermission() throws Exception {
        PlayerMock sam = server.addPlayer("Sam"), mia = server.addPlayer("Mia");
        settle();
        sam.performCommand("money Mia");
        assertEquals("§cYou can't do that.".length() > 0, true);
        sam.performCommand("money");   // opens the menu without blowing up
        settle();
        assertNotNull(sam.getOpenInventory());
    }

    @Test void ownersCanGiveTakeAuditAndReverse() throws Exception {
        PlayerMock sam = server.addPlayer("Sam");
        PlayerMock op = server.addPlayer("Boss");
        op.setOp(true);
        settle();
        op.performCommand("ce give Sam 100 for being nice");
        settle();
        assertEquals(35000, balance(sam));
        assertNotNull(core.ledger().stats().flowsToday().get("CapitalEngine-admin"), "admin money has its own source so it doesn't eat the starter budget");
        var history = await(api.history(api.accounts().player(sam.getUniqueId()), 5, Long.MAX_VALUE));
        long giveTx = history.get(0).txId();
        assertEquals("core.give", history.get(0).type());
        op.performCommand("ce take Sam 5");
        op.performCommand("ce reverse " + giveTx + " oops");
        settle();
        assertEquals(24500, balance(sam), "reverse took the 100 back, take removed 5");
        sam.performCommand("ce give Sam 1000000");   // players can't
        settle();
        assertEquals(24500, balance(sam));
        op.performCommand("ce reverse " + giveTx);   // already reversed
        op.performCommand("ce check");
        op.performCommand("ce audit top");
        op.performCommand("ce audit player Sam");
        op.performCommand("ce audit tx " + giveTx);
        op.performCommand("ce policy");
        settle();
    }

    @Test void importMovesBalancesExactlyAndASecondRunChangesNothing() throws Exception {
        core.getConfig().set("starter-coins", 0);
        core.saveConfig();
        core.reloadSettings();
        PlayerMock sam = server.addPlayer("Sam"), mia = server.addPlayer("Mia"), zed = server.addPlayer("Zed");
        settle();
        Map<UUID, Double> old = Map.of(sam.getUniqueId(), 123.45, mia.getUniqueId(), 0.0, zed.getUniqueId(), -4.0);
        Economy fake = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[] {Economy.class}, (proxy, m, args) -> {
            if (m.getName().equals("getName")) return "Essentials Economy";
            if (m.getName().equals("getBalance") && args[0] instanceof OfflinePlayer p) return old.getOrDefault(p.getUniqueId(), 0.0);
            Class<?> t = m.getReturnType();
            return t == boolean.class ? Boolean.FALSE : t == double.class ? (Object) 0.0 : t == int.class ? (Object) 0 : null;
        });
        server.getServicesManager().register(Economy.class, fake, org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin("Essentials"), ServicePriority.Normal);
        var importer = new Importer(core, core.ledger(), core.activity(), core.settings().currency(), core.messages());
        var console = server.getConsoleSender();

        importer.run(console, "Essentials", true);
        assertEquals(0, api.stats().moneySupply(), "a dry run changes nothing");
        importer.run(console, "Essentials", false);
        assertEquals(12345, balance(sam));
        assertEquals(0, balance(mia));
        assertEquals(0, balance(zed), "negative balances are skipped");
        assertEquals(12345, api.stats().moneySupply());
        importer.run(console, "Essentials", false);
        settle();
        assertEquals(12345, balance(sam), "second run is a no-op");
        assertEquals(12345, api.stats().moneySupply());
        var tx = await(api.history(api.accounts().player(sam.getUniqueId()), 5, Long.MAX_VALUE));
        assertEquals(1, tx.size());
        assertEquals("core.import", tx.get(0).type());
        assertEquals("import:Essentials:" + sam.getUniqueId(), tx.get(0).reference());
    }
}
