package com.patelheet.capitalengine.market;

import static org.junit.jupiter.api.Assertions.*;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.core.CapitalEnginePlugin;
import com.patelheet.capitalengine.market.storage.Store;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.enchantments.Enchantment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The real CapitalEngine plus CapitalMarket: coins really move, escrow stays exact, crashes recover. */
class MarketFlowTest {
    ServerMock server;
    CapitalApi api;
    CapitalMarketPlugin market;
    MarketService svc;
    UUID sam, mia;
    PlayerMock samP, miaP;

    static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(10, TimeUnit.SECONDS); }

    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock();
        // both jars have a plugin.yml on the test classpath, so describe each plugin by hand
        MockBukkit.loadWith(CapitalEnginePlugin.class, descriptor("CapitalEngine"));
        api = server.getServicesManager().load(CapitalApi.class);
        market = MockBukkit.loadWith(CapitalMarketPlugin.class, descriptor("CapitalMarket"));
        PlayerMock s = server.addPlayer("Sam"), m = server.addPlayer("Mia");
        sam = s.getUniqueId(); mia = m.getUniqueId();
        samP = s; miaP = m;
        server.getScheduler().performTicks(2);   // market.recover() runs next tick
        svc = market.service();
        await(svc.recover());
    }

    static PluginDescriptionFile descriptor(String name) throws Exception {
        for (var url : java.util.Collections.list(MarketFlowTest.class.getClassLoader().getResources("plugin.yml"))) {
            try (var in = url.openStream()) { var d = new PluginDescriptionFile(in); if (d.getName().equals(name)) return d; }
        }
        throw new IllegalStateException("no plugin.yml for " + name);
    }

    @AfterEach void stop() { MockBukkit.unmock(); }

    long bal(UUID p) { return api.accounts().player(p).balance(); }

    @Test void quickSellAndBuyUseTheServerMarketMaker() throws Exception {
        long supply = api.stats().moneySupply(), before = bal(sam);
        await(svc.deposit(sam, "iron_ingot", 10));
        var sold = await(svc.quickSell(sam, "iron_ingot", 10));
        assertNull(sold.error());
        assertEquals(10, sold.qty());
        assertEquals(before + sold.coins(), bal(sam));
        assertTrue(sold.coins() > 0 && sold.coins() < 10 * 500, "the server pays below the 5 coin base price");
        assertEquals(supply + sold.coins(), api.stats().moneySupply(), "new money is recorded: no treasury yet");

        var bought = await(svc.quickBuy(mia, "iron_ingot", 10, Long.MAX_VALUE));
        assertEquals(10, bought.qty());
        assertEquals(10, await(svc.stash(mia)).get("iron_ingot")[0]);
        assertEquals(640, svc.store.stock("iron_ingot"), "a fresh server starts with its target stock, so prices start at base");
        svc.store.setStock("iron_ingot", 0);
        assertEquals("buy-no-stock", await(svc.quickBuy(mia, "iron_ingot", 1, Long.MAX_VALUE)).error(), "the server only sells what it holds");
    }

    @Test void playersTradeWithAFeeAndEscrowStaysExact() throws Exception {
        await(svc.deposit(sam, "name_tag", 15));
        long samBefore = bal(sam), miaBefore = bal(mia), treasury = api.treasury().balance();

        var listing = await(svc.list(sam, "name_tag", 10, 1000));
        assertTrue(listing.orderId() > 0);
        assertEquals(10, await(svc.stash(sam)).get("name_tag")[1], "listed items are locked");
        assertEquals("not-enough-items", await(svc.list(sam, "name_tag", 10, 1000)).error(), "locked items can't be listed twice");

        var buy = await(svc.quickBuy(mia, "name_tag", 10, 10_000));
        assertEquals(10_000, buy.coins());
        assertEquals(samBefore + 9_800, bal(sam), "seller pays the 2% fee");
        assertEquals(miaBefore - 10_000, bal(mia));
        assertEquals(treasury + 200, api.treasury().balance());

        // a wanted order crossed by a listing trades at the resting (wanted) price
        var want = await(svc.wanted(mia, "name_tag", 5, 500));
        assertEquals(2_500, svc.escrow().balance());
        var sell = await(svc.list(sam, "name_tag", 5, 400));
        assertEquals(5, sell.qty());
        assertEquals(2_500 - 50, sell.coins());
        assertEquals(0, svc.escrow().balance());
        assertEquals(svc.store.buyEscrow(), svc.escrow().balance());

        var rest = await(svc.wanted(mia, "name_tag", 3, 100));
        assertEquals(300, svc.escrow().balance());
        assertTrue(await(svc.cancel(mia, rest.orderId())));
        assertEquals(0, svc.escrow().balance());
        assertEquals(0, svc.store.buyEscrow());
        assertTrue(want.orderId() > 0);
    }

    @Test void jobsPayFromEscrowAndFromTheDailyBudget() throws Exception {
        await(svc.deposit(mia, "name_tag", 4));
        var post = await(svc.jobs.post(sam, "name_tag", 10, 300));
        assertEquals(3_000, svc.jobs.escrow().balance());
        var d = await(svc.jobs.deliver(mia, post.orderId(), 4));
        assertEquals(1_200, d.coins());
        assertEquals(1_800, svc.jobs.escrow().balance());
        assertEquals(4, await(svc.stash(sam)).get("name_tag")[0], "player jobs deliver to the poster's Stash");
        assertTrue(await(svc.jobs.cancel(sam, post.orderId())).error() == null);
        assertEquals(0, svc.jobs.escrow().balance());

        // server job: paid through serverPay, and stops when the budget is used up
        long id = svc.store.insertJob(null, "name_tag", 1000, 100, "test", 0, 0, "OPEN");
        await(svc.deposit(mia, "name_tag", 1000));
        long budget = svc.policy.jobs();
        var first = await(svc.jobs.deliver(mia, id, 1000));
        assertEquals(Math.min(1000, budget / 100), first.qty());
        if (first.qty() < 1000) assertEquals("job-paused", await(svc.jobs.deliver(mia, id, 10)).error());
    }

    @Test void interruptedTradesAreFinishedOrRolledBack() throws Exception {
        svc.store.setStock("iron_ingot", 5);
        long supply = api.stats().moneySupply();
        // crash after the money moved: the pending row must be finished
        var paid = new Store.Trade(0, "iron_ingot", mia.toString(), null, "SELL", 650, 5, 0, 0, 0, 0, null);
        long id = await(CompletableFuture.supplyAsync(() -> svc.store.insertPending(paid, 0), svc.worker));
        assertTrue(await(api.transaction(market, "market.server_sell", "test").reference("fill:" + id).move(api.accounts().player(mia), api.treasury(), 3250).commit()).success());
        // crash before the money moved: the pending row must be dropped
        var unpaid = new Store.Trade(0, "iron_ingot", mia.toString(), null, "SELL", 650, 5, 0, 0, 0, 0, null);
        long id2 = await(CompletableFuture.supplyAsync(() -> svc.store.insertPending(unpaid, 0), svc.worker));

        await(svc.recover());
        assertEquals(5, await(svc.stash(mia)).get("iron_ingot")[0], "only the paid trade delivered");
        assertEquals(0, svc.store.stock("iron_ingot"));
        assertTrue(svc.store.pendingTrades().isEmpty());
        assertNotEquals(id, id2);
    }

    @Test void dayAndWeekTicksRunTheAutopilotAndKeepEscrowExact() throws Exception {
        await(svc.deposit(sam, "iron_ingot", 20));
        await(svc.quickSell(sam, "iron_ingot", 20));
        await(svc.wanted(mia, "name_tag", 5, 500));
        long week = api.stats().weekIndex();
        svc.onDay(api.stats(), api.stats().dayIndex(), false);
        svc.onWeek(week + 1, false);
        svc.onWeek(week + 2, true);                 // catch-up weeks never steer the economy
        await(svc.run(() -> null));                 // wait for the worker to drain
        assertEquals(week + 1, svc.policy.week());
        assertEquals(1, svc.store.weeksRun() - 1);
        var ind = new MarketPolicyService(svc);
        assertEquals(svc.policy.buyer(), ind.budget("buyer").getAsLong());
        assertTrue(ind.budget("nonsense").isEmpty());
        assertEquals(8, ind.indicators().size());
        assertEquals(svc.store.buyEscrow(), svc.escrow().balance());
    }

    /** Lets the worker finish and runs whatever it queued for the players' threads. */
    void settle() throws Exception {
        for (int i = 0; i < 3; i++) { await(svc.run(() -> null)); server.getScheduler().performTicks(2); }
    }

    @Test void bigDumpsArePipelinedAndAddUp() throws Exception {
        var p = svc.policy;   // plenty of budget, so the whole dump goes through (10 fills of 64)
        svc.policy = new com.patelheet.capitalengine.market.autopilot.Autopilot.State(p.week(), p.rate(), p.savings(), p.jobs(), 10_000_000, p.spread(), p.index(), p.inflation(), p.newMoney());
        long before = bal(sam), supply = api.stats().moneySupply();
        await(svc.deposit(sam, "cobblestone", 640));
        var r = await(svc.quickSell(sam, "cobblestone", 640));
        assertNull(r.error());
        assertEquals(640, r.qty());
        assertEquals(before + r.coins(), bal(sam));
        assertEquals(supply + r.coins(), api.stats().moneySupply(), "every coin came from the Mint, and is on the ledger");
        assertEquals(640 + 640, svc.store.stock("cobblestone"));
        assertTrue(svc.store.pendingTrades().isEmpty());
    }

    @Test void enchantedBooksAndPotionsTradeByKeyAndCustomItemsDont() throws Exception {
        var book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(m -> ((EnchantmentStorageMeta) m).addStoredEnchant(Enchantment.SHARPNESS, 5, true));
        assertEquals("enchanted_book{sharpness:5}", Items.keyOf(book));
        assertTrue(Items.one("enchanted_book{sharpness:5}").isSimilar(book));
        assertEquals("enchanted_book{sharpness:5}", Items.parse("Enchanted Book{sharpness:5}"));
        var named = new ItemStack(Material.DIAMOND);
        named.editMeta(m -> m.setDisplayName("Special"));
        assertNull(Items.keyOf(named));
        var worn = new ItemStack(Material.DIAMOND_PICKAXE);
        worn.editMeta(m -> ((org.bukkit.inventory.meta.Damageable) m).setDamage(10));
        assertNull(Items.keyOf(worn));
        assertEquals("diamond", Items.keyOf(new ItemStack(Material.DIAMOND, 7)));
        assertNull(Items.parse("not an item"));
    }

    @Test void buildJobsPayTheBuilderOrSettleADispute() throws Exception {
        long samBefore = bal(sam), miaBefore = bal(mia);
        var job = await(svc.jobs.postBuild(sam, 5_000, "a small house"));
        assertEquals(5_000, svc.jobs.escrow().balance());
        assertEquals("job-own", await(svc.jobs.accept(sam, job.orderId())).error());
        assertNull(await(svc.jobs.accept(mia, job.orderId())).error());
        assertEquals("job-not-yours", await(svc.jobs.finish(mia, job.orderId(), true)).error(), "only the poster can release the pay");
        assertNull(await(svc.jobs.finish(sam, job.orderId(), true)).error());
        assertEquals(miaBefore + 5_000, bal(mia));
        assertEquals(samBefore - 5_000, bal(sam));
        assertEquals(0, svc.jobs.escrow().balance());

        var dispute = await(svc.jobs.postBuild(sam, 3_000, "a bridge"));
        await(svc.jobs.accept(mia, dispute.orderId()));
        await(svc.jobs.finish(mia, dispute.orderId(), false));
        assertEquals(3_000, svc.jobs.escrow().balance(), "a disputed job keeps its pay held");
        assertNull(await(svc.jobs.settle(dispute.orderId(), false)).error());
        assertEquals(samBefore - 5_000, bal(sam), "refunded to the poster");
        assertEquals(0, svc.jobs.escrow().balance());
        assertEquals(svc.store.jobsEscrow(), svc.jobs.escrow().balance());
    }

    @Test void serverJobsComeFromProjectsAndOversupply() throws Exception {
        var file = new java.io.File(market.getDataFolder(), "projects.yml");
        java.nio.file.Files.writeString(file.toPath(), "railway:\n  needs: { iron_ingot: 500, rail: 200 }\n");
        svc.store.setStock("wheat", 5000);   // more than 2x the target
        svc.onDay(api.stats(), api.stats().dayIndex(), false);
        await(svc.run(() -> null));
        var open = await(svc.jobs.open());
        assertTrue(open.stream().anyMatch(j -> "railway".equals(j.project()) && "iron_ingot".equals(j.item()) && j.total() == 500));
        assertTrue(open.stream().anyMatch(j -> "railway".equals(j.project()) && "rail".equals(j.item())));
        assertTrue(open.stream().anyMatch(j -> j.project() == null && j.poster() == null && "wheat".equals(j.item())), "the town needs what it has too much of");
        long jobs = open.stream().filter(j -> "railway".equals(j.project())).count();
        svc.onDay(api.stats(), api.stats().dayIndex(), false);
        await(svc.run(() -> null));
        assertEquals(jobs, await(svc.jobs.open()).stream().filter(j -> "railway".equals(j.project())).count(), "projects aren't posted twice");

        // delivering to "the town needs" also uses up as many of the server's own extra items
        var town = open.stream().filter(j -> j.project() == null && j.poster() == null && "wheat".equals(j.item())).findFirst().orElseThrow();
        var p = svc.policy;   // plenty of jobs budget, so the budget isn't what stops a delivery
        svc.policy = new com.patelheet.capitalengine.market.autopilot.Autopilot.State(p.week(), p.rate(), p.savings(), 10_000_000, p.buyer(), p.spread(), p.index(), p.inflation(), p.newMoney());
        await(svc.deposit(mia, "wheat", 100));
        long stock = svc.store.stock("wheat");
        var d = await(svc.jobs.deliver(mia, town.id(), 100));
        assertTrue(d.qty() > 0, String.valueOf(d.error()));
        assertEquals(stock - d.qty(), svc.store.stock("wheat"));
        svc.store.setStock("wheat", 650);   // only 10 above the 640 target now
        await(svc.deposit(mia, "wheat", 50));
        var more = await(svc.jobs.deliver(mia, town.id(), 50));
        assertTrue(more.qty() > 10, String.valueOf(more.error()));
        assertEquals(640, svc.store.stock("wheat"), "never below the target");
    }

    /** Waits for a worker task that itself waits on a region task (project chests), ticking the server meanwhile. */
    <T> T ticking(CompletableFuture<T> f) throws Exception {
        for (int i = 0; i < 200 && !f.isDone(); i++) { server.getScheduler().performTicks(1); Thread.sleep(5); }
        return await(f);
    }

    @Test void projectDeliveriesReachTheChestEvenAfterACrash() throws Exception {
        var world = samP.getWorld();
        var chest = world.getBlockAt(5, 60, 5);
        chest.setType(Material.CHEST);
        Files.writeString(new java.io.File(market.getDataFolder(), "projects.yml").toPath(),
                "railway:\n  needs: { iron_ingot: 500 }\n  deliver-to: chest@" + world.getName() + ",5,60,5\n");
        await(svc.replan());
        var job = await(svc.jobs.open()).stream().filter(j -> "railway".equals(j.project())).findFirst().orElseThrow();
        await(svc.deposit(mia, "iron_ingot", 10));
        var d = ticking(svc.jobs.deliver(mia, job.id(), 10));
        assertEquals(10, d.qty(), String.valueOf(d.error()));
        settle();
        var inv = ((org.bukkit.block.Container) chest.getState()).getInventory();
        assertEquals(10, inv.all(Material.IRON_INGOT).values().stream().mapToInt(ItemStack::getAmount).sum());
        assertTrue(svc.store.deliveries("CHEST").isEmpty(), "done once the items are in");

        // a crash after the pay, before the items reached the chest: they are sent again on start
        await(svc.deposit(mia, "iron_ingot", 4));
        long id = await(svc.run(() -> svc.store.insertDelivery(job.id(), mia, 4, 4 * job.pay(), svc.day(), true)));
        await(svc.run(() -> { svc.store.finishDelivery(id, job.id(), mia, 4, svc.targetStock, true); return null; }));
        assertEquals(1, svc.store.deliveries("CHEST").size());
        await(svc.recover());
        settle();
        inv = ((org.bukkit.block.Container) chest.getState()).getInventory();
        assertEquals(14, inv.all(Material.IRON_INGOT).values().stream().mapToInt(ItemStack::getAmount).sum());
        assertTrue(svc.store.deliveries("CHEST").isEmpty());
        assertEquals(0, await(svc.free(mia, "iron_ingot")), "the delivered items left the Stash once");
    }

    @Test void stashMovesAreWrittenBeforeTheInventoryChanges() throws Exception {
        var a = market.actions();
        a.stalls = new Stalls(svc, market.menus()) { @Override public boolean canWithdraw(org.bukkit.entity.Player p) { return true; } };
        settle();
        // deposit: the player drops 4 of the 10 before the Stash answers, so only 6 are recorded
        samP.getInventory().clear();
        samP.getInventory().addItem(new ItemStack(Material.IRON_INGOT, 10));
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        a.deposit(samP, "iron_ingot", 10, () -> done.set(true));
        samP.getInventory().removeItem(new ItemStack(Material.IRON_INGOT, 4));
        settle();
        assertTrue(done.get());
        assertArrayEquals(new int[]{6, 0}, await(svc.stash(sam)).get("iron_ingot"));
        assertEquals(0, a.count(samP, "iron_ingot"));

        // withdraw into a full inventory: nothing is given, so nothing leaves the Stash
        for (int i = 0; i < samP.getInventory().getSize(); i++) samP.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        a.withdraw(samP, "iron_ingot", 6, () -> { });
        settle();
        assertArrayEquals(new int[]{6, 0}, await(svc.stash(sam)).get("iron_ingot"));
        samP.getInventory().clear();
        a.withdraw(samP, "iron_ingot", 6, () -> { });
        settle();
        assertEquals(6, a.count(samP, "iron_ingot"));
        assertNull(await(svc.stash(sam)).get("iron_ingot"));

        // a crash between the Stash write and the inventory: the items stay in the Stash (held, then freed on start)
        assertTrue(await(svc.holdIn(sam, "gold_ingot", 5)));
        assertEquals(0, await(svc.free(sam, "gold_ingot")), "held items can't be sold yet");
        await(svc.recover());
        assertEquals(5, await(svc.free(sam, "gold_ingot")));

        // logging off mid-move: the 3 diamonds end up in the bag or the Stash, never both and never neither
        samP.getInventory().addItem(new ItemStack(Material.DIAMOND, 3));
        a.deposit(samP, "diamond", 3, () -> { });
        samP.disconnect();
        settle();
        await(svc.recover());
        assertEquals(3, a.count(samP, "diamond") + await(svc.stash(sam)).getOrDefault("diamond", new int[]{0, 0})[0]);
    }

    @Test void tutorialPaysEachStepOnceAndInOrder() throws Exception {
        settle();
        long before = bal(sam);
        svc.tutorial.advance(sam, 2);                 // out of order: ignored
        settle();
        assertEquals(before, bal(sam));
        svc.tutorial.advance(sam, 1);
        svc.tutorial.advance(sam, 1);                 // a repeat: still one reward
        settle();
        assertEquals(before + 2_000, bal(sam));
        svc.tutorial.advance(sam, 2);
        settle();
        assertEquals(before + 4_000, bal(sam));
        svc.tutorial.skip(sam);
        svc.tutorial.advance(sam, 3);
        settle();
        assertEquals(before + 4_000, bal(sam), "a skipped tutorial pays nothing more");
    }

    @Test void stallsArePlacedOnTopWithRoomAboveAndGuardTheStash() throws Exception {
        settle();
        // MockBukkit can't spawn display entities, so the barrel and the rules are tested for real and only the hologram is stubbed
        var stalls = new Stalls(svc, market.menus()) {
            @Override void spawnHologram(org.bukkit.block.Block barrel) { }
            @Override void removeHologram(org.bukkit.block.Block barrel) { }
        };
        assertFalse(stalls.canWithdraw(samP));
        var world = samP.getWorld();
        int x = samP.getLocation().getBlockX() + 3, z = samP.getLocation().getBlockZ(), y = samP.getLocation().getBlockY();
        for (int dy = 0; dy <= 4; dy++) world.getBlockAt(x, y + dy, z).setType(Material.AIR);
        var ground = world.getBlockAt(x, y, z);
        ground.setType(Material.SAND);

        world.getBlockAt(x, y + 3, z).setType(Material.STONE);   // not enough room above
        assertEquals(Stalls.Result.NO_SPACE, stalls.create(ground));
        assertEquals(Material.AIR, world.getBlockAt(x, y + 1, z).getType(), "nothing is placed when there is no room");
        world.getBlockAt(x, y + 3, z).setType(Material.AIR);

        assertEquals(Stalls.Result.CREATED, stalls.create(ground));
        assertEquals(Material.SAND, ground.getType(), "the original block is untouched");
        var barrel = world.getBlockAt(x, y + 1, z);
        assertEquals(Material.BARREL, barrel.getType());
        assertTrue(stalls.isStall(barrel));
        assertEquals(Stalls.Result.ALREADY, stalls.create(ground));
        assertTrue(stalls.canWithdraw(samP));

        assertEquals(Stalls.Result.REMOVED, stalls.remove(barrel));
        assertEquals(Material.AIR, barrel.getType());
        assertEquals(Material.SAND, ground.getType());
        assertEquals(Stalls.Result.NOT_A_STALL, stalls.remove(ground), "removal never touches a block that isn't a stall");
        assertFalse(stalls.canWithdraw(samP));
    }

    @Test void aSellThatTheBudgetCannotCoverSaysWhyAndShowsCapacity() throws Exception {
        // a fresh one-player server buys about 60 coins a day, and a diamond's bid is 42: a second diamond sale finds the budget empty
        await(svc.deposit(sam, "diamond", 10));
        var info = await(svc.prices(List.of("diamond"))).get("diamond");
        assertTrue(info.canBuy() >= 1 && info.canBuy() < 10, "capacity is shown: " + info.canBuy());
        var quote = await(svc.quote(sam, "diamond", 10, false));
        assertTrue(quote.qty() < 10);
        assertEquals("budget", quote.reason());
        var first = await(svc.quickSell(sam, "diamond", 10));
        assertNull(first.error());
        assertTrue(first.qty() < 10, "only what the budget covers is bought");
        var second = await(svc.quickSell(sam, "diamond", 5));
        assertEquals("sell-budget", second.error(), "and the player is told the budget is why");
    }
    @Test void menuConfirmScreenSellsOnlyOnYes() throws Exception {
        await(svc.deposit(sam, "iron_ingot", 5));
        samP.getInventory().setItemInMainHand(new ItemStack(Material.IRON_INGOT, 3));
        long before = bal(sam);
        market.menus().item(samP, "iron_ingot");
        settle();
        samP.simulateInventoryClick(samP.getOpenInventory(), Menus.SELL_ALL_SLOT[0]);   // "Sell All"
        settle();
        samP.simulateInventoryClick(samP.getOpenInventory(), 15);              // No
        settle();
        assertEquals(before, bal(sam), "No sells nothing");
        assertEquals(3, samP.getInventory().getItemInMainHand().getAmount());

        market.menus().item(samP, "iron_ingot");
        settle();
        samP.simulateInventoryClick(samP.getOpenInventory(), Menus.SELL_ALL_SLOT[0]);
        settle();
        samP.simulateInventoryClick(samP.getOpenInventory(), 11);              // Yes
        settle();
        assertTrue(bal(sam) > before, "Yes sells");
        assertEquals(0, await(svc.stash(sam)).getOrDefault("iron_ingot", new int[]{0, 0})[0], "stash and bag items were both sold");
        assertTrue(samP.getInventory().getItemInMainHand().getType().isAir());
    }

    @Test void typedAnswersAreCollectedInOrderAndCancelStops() throws Exception {
        var got = new java.util.concurrent.atomic.AtomicReference<List<String>>();
        var a = market.actions();
        a.ask(samP, "btn-list-mine", List.of("qty", "price"), got::set);
        assertTrue(a.answer(samP, "5"));
        settle();
        assertNull(got.get(), "still waiting for the price");
        assertTrue(a.answer(samP, "12"));
        settle();
        assertEquals(List.of("5", "12"), got.get());
        assertFalse(a.answer(samP, "hello"), "ordinary chat isn't swallowed");

        got.set(null);
        a.ask(samP, "btn-list-mine", List.of("qty", "price"), got::set);
        assertTrue(a.answer(samP, "cancel"));
        settle();
        assertNull(got.get());
        assertFalse(a.answer(samP, "5"));
    }

    private String received(PlayerMock p) {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = p.nextMessage()) != null) sb.append(m).append('\n');
        return sb.toString();
    }

    @Test void everyHelpTopicHasRealTextAndBothTheCommandAndTheMenuButtonShowIt() throws Exception {
        for (String topic : Actions.HELP_TOPICS) {
            String text = svc.msg.plain("help-" + topic);
            assertFalse(text.contains("Missing message"), "help-" + topic + " is missing from messages.yml");
            assertTrue(text.length() > 80, "help-" + topic + " should say something useful");
        }
        for (String key : new String[]{"help-overview", "help-topics", "help-typed", "help-unknown", "btn-help", "lore-help"})
            assertFalse(svc.msg.plain(key, "topic", "x").contains("Missing message"), key);
        assertTrue(svc.msg.plain("help-topics").contains("[Getting Started]"), "the topic buttons render: " + svc.msg.plain("help-topics"));

        settle();
        samP.addAttachment(market, "capitalmarket.use", true);   // MockBukkit does not apply plugin.yml defaults
        received(samP);
        samP.performCommand("market help");
        String overview = received(samP);
        assertTrue(overview.contains("Market Help"), "overview was: " + overview);
        samP.performCommand("market help sell");
        String sell = received(samP);
        assertTrue(sell.contains("Selling to the Market") && sell.contains("/market help"), "a topic, with the typed fallback; got: " + sell);
        samP.performCommand("market help nonsense");
        String bad = received(samP);
        assertTrue(bad.contains("no help topic"), "got: " + bad);

        market.menus().main(samP);
        settle();
        received(samP);
        samP.simulateInventoryClick(samP.getOpenInventory(), 26);   // the Help book in the corner
        settle();
        String viaMenu = received(samP);
        assertTrue(viaMenu.contains("Market Help"), "the menu button shows the overview in chat; got: " + viaMenu);
    }

    @Test void anOlderEditedMessagesFileStillWorksAndGainsTheNewEntries() throws Exception {
        var file = new java.io.File(market.getDataFolder(), "messages.yml");
        // an owner's file from an earlier version: the prefix is customised and the help entries did not exist yet
        String old = Files.readAllLines(file.toPath()).stream().filter(l -> !l.startsWith("help-start:")).map(l -> l.startsWith("prefix:") ? "prefix: \"[OWNER] \"" : l)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n";
        Files.writeString(file.toPath(), old);
        var messages = new Messages(market);
        assertEquals("[OWNER] ", messages.plain("prefix"), "the owner's edit is kept");
        assertFalse(messages.plain("help-start").contains("Missing message"), "a key the owner's file lacks falls back to the jar's text");
        assertTrue(Files.readString(file.toPath()).contains("help-start:"), "and is added to the file so the owner can see and edit it");
    }
}
