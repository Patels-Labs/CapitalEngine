package com.patelheet.capitalengine.trade;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.core.CapitalEnginePlugin;
import com.patelheet.capitalengine.trade.port.Slide;
import com.patelheet.capitalengine.trade.world.Booms;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The real CapitalEngine plus CapitalTrade: coins really move, quotas hold, items always come back, crashes recover. */
class TradeFlowTest {
    static final Actions.Confirm YES = (p, key, kv, yes) -> yes.run();

    ServerMock server;
    CapitalApi api;
    CapitalEnginePlugin core;
    CapitalTradePlugin trade;
    TradeService svc;
    Ports ports;
    UUID sam;
    PlayerMock samP;

    static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(10, TimeUnit.SECONDS); }

    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock();
        // both jars have a plugin.yml on the test classpath, so describe each plugin by hand
        core = MockBukkit.loadWith(CapitalEnginePlugin.class, descriptor("CapitalEngine"));
        api = server.getServicesManager().load(CapitalApi.class);
        trade = MockBukkit.loadWith(CapitalTradePlugin.class, descriptor("CapitalTrade"));
        samP = server.addPlayer("Sam");
        sam = samP.getUniqueId();
        server.getScheduler().performTicks(2);   // recover() runs next tick
        svc = trade.service();
        await(svc.recover());
        // MockBukkit can't spawn display entities, so the Port is real and only its hologram is stubbed
        ports = new Ports(svc, trade.menus()) {
            @Override void spawnHologram(org.bukkit.block.Block barrel) { }
            @Override void removeHologram(org.bukkit.block.Block barrel) { }
        };
        trade.actions().ports = ports;
        samP.addAttachment(trade, "capitaltrade.use", true);   // MockBukkit does not apply plugin.yml defaults
    }

    static PluginDescriptionFile descriptor(String name) throws Exception {
        for (var url : java.util.Collections.list(TradeFlowTest.class.getClassLoader().getResources("plugin.yml"))) {
            try (var in = url.openStream()) { var d = new PluginDescriptionFile(in); if (d.getName().equals(name)) return d; }
        }
        throw new IllegalStateException("no plugin.yml for " + name);
    }

    @AfterEach void stop() { MockBukkit.unmock(); }

    long bal(UUID p) { return api.accounts().player(p).balance(); }

    /** Lets the worker finish and runs whatever it queued for the players' threads. */
    void settle() throws Exception {
        for (int i = 0; i < 4; i++) { await(svc.idle()); server.getScheduler().performTicks(2); }
    }

    int held(PlayerMock p, Material m) {
        return Arrays.stream(p.getInventory().getStorageContents()).filter(Objects::nonNull).filter(s -> s.getType() == m).mapToInt(ItemStack::getAmount).sum();
    }

    String chat(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) sb.append(m).append('\n');
        return sb.toString().replaceAll("§.", "");
    }

    /** A Port right next to Sam. */
    void portHere() {
        var w = samP.getWorld();
        int x = samP.getLocation().getBlockX() + 2, y = samP.getLocation().getBlockY(), z = samP.getLocation().getBlockZ();
        for (int dy = 0; dy <= 4; dy++) w.getBlockAt(x, y + dy, z).setType(Material.AIR);
        w.getBlockAt(x, y, z).setType(Material.STONE);
        assertEquals(Ports.Result.CREATED, ports.create(w.getBlockAt(x, y, z), "harbour"));
    }

    @Test void anExportPaysExactlyTheQuotedNewCoinsAndTheItemsAreGone() throws Exception {
        portHere();
        samP.getInventory().addItem(new ItemStack(Material.WHEAT, 64));
        long before = bal(sam), supply = api.stats().moneySupply(), treasury = api.treasury().balance();
        var q = await(svc.quoteExport("wheat", 64));
        assertEquals(64, q.qty());
        assertEquals(Slide.exportTotal(80, 0, 64, 128, 0.5), q.total(), "one player: quota 128, base price 0.80");
        trade.actions().startExport(samP, "wheat", 0, YES);
        settle();
        assertEquals(0, held(samP, Material.WHEAT), "exported items leave the game");
        assertEquals(before + q.total(), bal(sam));
        assertEquals(supply + q.total(), api.stats().moneySupply(), "export money is new money from outside");
        assertEquals(treasury, api.treasury().balance(), "and it never drains the Treasury");
        assertTrue(chat(samP).contains("Shipped 64 Wheat"));
        assertTrue(svc.store.pending().isEmpty());
    }

    @Test void theQuotaStopsMoneyPrintingAndTheRestComesBack() throws Exception {
        portHere();
        long supply = api.stats().moneySupply();
        samP.getInventory().addItem(new ItemStack(Material.WHEAT, 64), new ItemStack(Material.WHEAT, 64), new ItemStack(Material.WHEAT, 64));
        trade.actions().startExport(samP, "wheat", 0, YES);
        settle();
        long paid = Slide.exportTotal(80, 0, 128, 128, 0.5);
        assertEquals(supply + paid, api.stats().moneySupply(), "only the quota was paid for");
        assertEquals(64, held(samP, Material.WHEAT), "only what the ships take leaves the inventory");
        var r = await(svc.export(sam, "wheat", 10));
        assertEquals("export-quota", r.error());
        assertEquals(0, r.qty());
        assertEquals(supply + paid, api.stats().moneySupply(), "a full quota prints nothing");
        assertEquals(10, await(svc.cargo(sam)).get("wheat"), "refused items wait in Cargo");
        assertEquals(0L, await(svc.quotaLeft("wheat", true)));

        // more active players, more quota
        for (UUID u : new UUID[]{sam, UUID.randomUUID(), UUID.randomUUID()}) core.activity().recordActivity(u, 10);
        assertEquals(3 * 128 - 128, await(svc.quotaLeft("wheat", true)));
    }

    @Test void whatTheShipsCantTakeGoesToCargoInTheSameStep() throws Exception {
        var r = await(svc.export(sam, "carrot", 200));   // the caller has taken 200; the quota is 128
        assertEquals(128, r.qty());
        assertEquals("export-quota", r.error());
        assertEquals(72, await(svc.cargo(sam)).get("carrot"));
        assertEquals("DONE", svc.store.shipment(1).status());
    }

    @Test void exportsStayInsideCoresDailyCapAndTheItemsAlwaysGoBack() throws Exception {
        portHere();
        assertNull(core.setPolicy("CapitalTrade", "issue-per-day", "5"));   // 500 units: one iron ingot (2.60) fits, two don't
        samP.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 5));
        var q = await(svc.quoteExport("iron_ingot", 5));
        assertEquals(1, q.qty());
        assertEquals("budget", q.reason());
        trade.actions().startExport(samP, "iron_ingot", 5, YES);
        settle();
        assertEquals(4, held(samP, Material.IRON_INGOT), "the four the budget couldn't cover never left");
        trade.actions().startExport(samP, "iron_ingot", 4, YES);
        settle();
        assertEquals(4, held(samP, Material.IRON_INGOT));
        assertTrue(chat(samP).contains("spent all their coins"));
        assertTrue(api.policy(trade).issueRemainingToday() >= 0);
    }

    @Test void importsAreOffByDefault() throws Exception {
        portHere();
        assertTrue(svc.imports.isEmpty());
        assertEquals("import-off", await(svc.importItems(sam, "sponge", 1, Long.MAX_VALUE)).error());
        trade.menus().main(samP);
        settle();
        var top = samP.getOpenInventory().getTopInventory();
        assertTrue(top.getHolder() instanceof Menu, "the Port menu opened");
        assertFalse(top.contains(Material.EMERALD), "no Import tab when imports.yml is empty");
        assertTrue(top.contains(Material.OAK_BOAT));
    }

    @Test void importsWhenOnAreASinkWithTheTariffToTheTreasury() throws Exception {
        Files.writeString(trade.getDataFolder().toPath().resolve("imports.yml"), "sponge: { base: 40, quota-per-active-player: 1, tariff: 0.25 }\n");
        trade.reload();
        await(svc.idle());
        portHere();
        assertTrue(await(api.issue(core, api.accounts().player(sam), 20_000, "test.grant", "test")).success());
        long before = bal(sam), supply = api.stats().moneySupply(), treasury = api.treasury().balance();
        var q = await(svc.quoteImport("sponge", 1));
        assertEquals(6250, q.total(), "40 coins, plus 25% tariff, plus the slide for the only one today");
        assertEquals(1250, q.tariff());
        trade.actions().startImport(samP, "sponge", 1, YES);
        settle();
        assertEquals(1, held(samP, Material.SPONGE));
        assertEquals(before - 6250, bal(sam));
        assertEquals(treasury + 1250, api.treasury().balance(), "the tariff stays home");
        assertEquals(supply - 5000, api.stats().moneySupply(), "the rest leaves the economy");
        assertEquals("import-quota", await(svc.importItems(sam, "sponge", 1, Long.MAX_VALUE)).error());

        trade.menus().main(samP);
        settle();
        assertTrue(samP.getOpenInventory().getTopInventory().contains(Material.EMERALD), "the Import tab is shown once imports exist");

        UUID poor = UUID.randomUUID();   // never joined, so no starter coins
        core.activity().recordActivity(sam, 10);
        core.activity().recordActivity(poor, 10);   // two active players: the quota is 2
        long poorBefore = bal(poor);
        assertEquals(1L, await(svc.quotaLeft("sponge", false)));
        assertEquals("err-funds", await(svc.importItems(poor, "sponge", 1, Long.MAX_VALUE)).error(), "core refuses: never below zero");
        assertEquals(poorBefore, bal(poor));
        assertTrue(await(svc.cargo(poor)).isEmpty(), "a failed import delivers nothing");
        assertEquals(1L, await(svc.quotaLeft("sponge", false)), "and frees its quota again");
        var failed = svc.store.pending();
        assertTrue(failed.isEmpty());
        assertEquals("FAILED", svc.store.shipment(2).status());
    }

    @Test void portNewsAnnouncesABoomAndPricesRise() throws Exception {
        double before = svc.worldPrice("pumpkin");
        await(svc.boom("pumpkin", 1.3));
        settle();
        assertEquals(before * 1.3, svc.worldPrice("pumpkin"), 1e-9);
        assertTrue(chat(samP).contains("Faraway lands want PUMPKIN this week! Prices are up 30%"));
        var news = await(svc.news());
        String plain = news.stream().map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c)).reduce("", String::concat);
        assertTrue(plain.contains("PUMPKIN"));
        assertTrue(plain.contains("Nothing has been shipped yet today"));

        long week = api.stats().weekIndex();
        svc.onWeek(week + 1, true);   // a catch-up week ends last week's boom and never starts one
        await(svc.idle());
        assertNull(svc.boomItem);
        assertEquals(before, svc.worldPrice("pumpkin"), 1e-9);
        svc.onWeek(week + 1, false);
        await(svc.idle());
        assertEquals(Booms.roll(12345, week + 1, 0.5, List.copyOf(svc.exports.keySet())), svc.boomItem);
        assertTrue(svc.indicators().stream().anyMatch(i -> i.key().equals("boom_multiplier")));
    }

    @Test void worldPricesMoveOncePerDayIncludingCatchUpDays() throws Exception {
        long day = api.stats().dayIndex();
        var start = svc.prices.get("iron_ingot");
        svc.onDay(api.stats(), day + 1, true);
        await(svc.idle());
        var moved = svc.prices.get("iron_ingot");
        assertEquals(day + 1, moved.day());
        assertEquals(start.price(), moved.prev());
        svc.onDay(api.stats(), day + 1, false);   // the same day again changes nothing
        await(svc.idle());
        assertEquals(moved, svc.prices.get("iron_ingot"));
        assertEquals(moved, svc.store.prices().get("iron_ingot"), "saved");
    }

    @Test void interruptedShipmentsAreFinishedOrHandedBack() throws Exception {
        long day = api.stats().dayIndex();
        // export, paid before the crash: finished, items stay gone
        long paid = await(svc.run(() -> svc.store.insertShipment(sam, "wheat", "EXPORT", 10, 790, 0, day, 0)));
        assertTrue(await(api.transaction(trade, "trade.export", "test").reference("exp:" + paid).issue(api.accounts().player(sam), 790).commit()).success());
        // export, crashed before the money: the items come back through Cargo
        long unpaid = await(svc.run(() -> svc.store.insertShipment(sam, "wheat", "EXPORT", 7, 553, 0, day, 0)));
        // import, paid before the crash: the items are delivered through Cargo
        long imp = await(svc.run(() -> svc.store.insertShipment(sam, "sponge", "IMPORT", 1, 100, 20, day, 0)));
        assertTrue(await(api.transaction(trade, "trade.import", "test").reference("imp:" + imp).destroy(api.accounts().player(sam), 100).commit()).success());
        // import, crashed before the money: nothing happens
        long impUnpaid = await(svc.run(() -> svc.store.insertShipment(sam, "sponge", "IMPORT", 3, 300, 60, day, 0)));

        await(svc.recover());
        assertEquals("DONE", svc.store.shipment(paid).status());
        assertEquals("FAILED", svc.store.shipment(unpaid).status());
        assertEquals("DONE", svc.store.shipment(imp).status());
        assertEquals("FAILED", svc.store.shipment(impUnpaid).status());
        var cargo = await(svc.cargo(sam));
        assertEquals(7, cargo.get("wheat"));
        assertEquals(1, cargo.get("sponge"));
        assertEquals(2, cargo.size());
        assertTrue(svc.store.pending().isEmpty());

        portHere();
        samP.performCommand("trade cargo");
        settle();
        assertEquals(7, held(samP, Material.WHEAT));
        assertEquals(1, held(samP, Material.SPONGE));
        assertTrue(await(svc.cargo(sam)).isEmpty());
    }

    @Test void portsAreGuardedAndOnlyWorkNearby() throws Exception {
        assertFalse(ports.atPort(samP));
        trade.menus().main(samP);
        assertTrue(chat(samP).contains("You need to be at a Port"));
        portHere();
        assertTrue(ports.atPort(samP));
        var w = samP.getWorld();
        var barrel = w.getBlockAt(samP.getLocation().getBlockX() + 2, samP.getLocation().getBlockY() + 1, samP.getLocation().getBlockZ());
        assertEquals(Material.BARREL, barrel.getType());
        assertTrue(ports.isPort(barrel));
        assertEquals(Ports.Result.NAME_TAKEN, ports.create(w.getBlockAt(0, 100, 0), "Harbour"));
        assertEquals(Ports.Result.REMOVED, ports.remove("harbour"));
        assertEquals(Material.AIR, barrel.getType());
        assertFalse(ports.atPort(samP));
        assertEquals(Ports.Result.NOT_A_PORT, ports.remove("harbour"));
    }

    @Test void indicatorsAndHelpAreThere() throws Exception {
        await(svc.export(sam, "wheat", 16));
        var ind = svc.indicators();
        assertEquals(8, ind.size());
        double exp = ind.stream().filter(i -> i.key().equals("exports_value_today")).findFirst().orElseThrow().value();
        assertEquals(Slide.exportTotal(80, 0, 16, 128, 0.5), (long) exp);
        assertEquals("capitaltrade", svc.id());
        chat(samP);
        samP.performCommand("trade help");
        assertTrue(chat(samP).contains("Port Help"));
        for (String topic : Actions.HELP_TOPICS) {
            samP.performCommand("trade help " + topic);
            String out = chat(samP);
            assertFalse(out.contains("Missing message"), topic);
            assertFalse(out.contains("no help topic"), topic);
        }
    }
}
