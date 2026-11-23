package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.api.Account;
import com.patelheet.capitalengine.api.TxResult;
import com.patelheet.capitalengine.market.MarketService.Result;
import com.patelheet.capitalengine.market.storage.Store;
import com.patelheet.capitalengine.market.storage.Store.Job;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/**
 * The Jobs Board (market/Plan.md §5.8). Delivery jobs: "bring N, I pay X each", from players (pay held in escrow) and from
 * the server (owner projects, then items the town has too much of). Build jobs: one player accepts, the poster releases the
 * pay with Done or calls staff with Problem. Runs on the service's worker.
 */
public final class JobsBoard {
    private final MarketService svc;
    private final double premium;
    private final int expiryDays;
    private final Map<String, String> chests = new HashMap<>();      // project -> "world,x,y,z"
    private final Map<String, Double> premiums = new HashMap<>();    // project -> pay premium

    JobsBoard(MarketService svc, FileConfiguration cfg) {
        this.svc = svc;
        premium = cfg.getDouble("jobs.server-premium", 0.10);
        expiryDays = cfg.getInt("jobs.player-job-expiry-days", 7);
    }

    Account escrow() { return svc.api.accounts().plugin(svc.plugin, "jobs-escrow"); }

    private File projectsFile() { return new File(svc.plugin.getDataFolder(), "projects.yml"); }

    public CompletableFuture<List<Job>> open() { return svc.run(svc.store::openJobs); }

    public CompletableFuture<Job> get(long id) { return svc.run(() -> svc.store.job(id)); }

    // ---- posting -----------------------------------------------------------------------------------------------

    public CompletableFuture<Result> post(UUID p, String item, int qty, long pay) {
        return svc.run(() -> fund(p, svc.store.insertJob(p.toString(), item, qty, pay, null, svc.now(), svc.day() + expiryDays, "PENDING"), qty * pay));
    }

    public CompletableFuture<Result> postBuild(UUID p, long pay, String note) {
        return svc.run(() -> fund(p, svc.store.insertBuildJob(p.toString(), pay, note, svc.now(), svc.day() + expiryDays, "PENDING"), pay));
    }

    /** Holds the poster's pay in escrow; the job opens only once the money has moved. */
    private Result fund(UUID p, long id, long total) {
        TxResult r = svc.api.transaction(svc.plugin, "market.job", svc.msg.plain("memo-job-post", "id", id)).reference("jobpost:" + id).actor(p)
                .move(svc.acct(p.toString()), escrow(), total).commit().join();
        svc.store.jobStatus(id, svc.ok(r) ? "OPEN" : "CANCELLED");
        return svc.ok(r) ? new Result(1, total, null, id) : Result.err(svc.errKey(r.failure()));
    }

    // ---- build jobs --------------------------------------------------------------------------------------------

    public CompletableFuture<Result> accept(UUID p, long id) {
        return svc.run(() -> {
            Job j = svc.store.job(id);
            if (j == null || !j.kind().equals("BUILD") || !j.status().equals("OPEN")) return Result.err("job-none");
            if (p.toString().equals(j.poster())) return Result.err("job-own");
            svc.store.assign(id, p.toString());
            return new Result(1, j.pay(), null, id);
        });
    }

    /** The poster's Done (pays the builder) or Problem (calls staff); the builder may also raise a Problem. */
    public CompletableFuture<Result> finish(UUID p, long id, boolean done) {
        return svc.run(() -> {
            Job j = svc.store.job(id);
            if (j == null || !j.kind().equals("BUILD") || !j.status().equals("ACCEPTED")) return Result.err("job-none");
            boolean poster = p.toString().equals(j.poster());
            if (!poster && !(p.toString().equals(j.assignee()) && !done)) return Result.err("job-not-yours");
            if (!done) { svc.store.jobStatus(id, "DISPUTE"); return new Result(0, 0, null, id); }
            return payBuilder(j) ? new Result(1, j.pay(), null, id) : Result.err("err-other");
        });
    }

    /** Staff: {@code /jobs admin settle <id> pay|refund} for a disputed build job. */
    public CompletableFuture<Result> settle(long id, boolean pay) {
        return svc.run(() -> {
            Job j = svc.store.job(id);
            if (j == null || !j.kind().equals("BUILD") || !(j.status().equals("DISPUTE") || j.status().equals("ACCEPTED"))) return Result.err("job-none");
            return (pay ? payBuilder(j) : refund(j, "REFUNDED")) ? new Result(1, j.pay(), null, id) : Result.err("err-other");
        });
    }

    private boolean payBuilder(Job j) {
        TxResult r = svc.api.transaction(svc.plugin, "market.job", svc.msg.plain("memo-job-built", "id", j.id())).reference("jobdone:" + j.id())
                .move(escrow(), svc.acct(j.assignee()), j.pay()).commit().join();
        if (!svc.ok(r)) return false;
        svc.store.jobStatus(j.id(), "DONE");
        return true;
    }

    // ---- delivery jobs -----------------------------------------------------------------------------------------

    /** Delivers from the player's Stash. Player jobs pay from escrow, server jobs from the "jobs" budget. */
    public CompletableFuture<Result> deliver(UUID p, long jobId, int qty) {
        return svc.run(() -> {
            Store st = svc.store;
            Job j = st.job(jobId);
            if (j == null || !j.kind().equals("DELIVERY") || !j.status().equals("OPEN")) return Result.err("job-none");
            if (p.toString().equals(j.poster())) return Result.err("job-own");
            boolean server = j.poster() == null;
            int n = Math.min(Math.min(qty, j.left()), st.stashFree(p, j.item()));
            if (server) {   // stop at today's budget
                long left = svc.policy == null ? 0 : Math.max(0, svc.policy.jobs() - svc.jobPaidToday());
                n = (int) Math.min(n, left / j.pay());
                if (n == 0) return Result.err("job-paused");
            }
            boolean chest = j.project() != null && chestOf(j.project()) != null;
            if (chest) {   // the project chest must have room
                n = Math.min(n, room(j.project(), j.item()));
                if (n == 0) return Result.err("job-chest-full");
            }
            if (n <= 0) return Result.err("not-enough-items");
            long paid = n * j.pay(), day = svc.day();
            long id = st.insertDelivery(jobId, p, n, paid, day, server);
            var tx = svc.api.transaction(svc.plugin, server ? "market.server_job" : "market.job", svc.msg.plain("memo-job-pay", "id", jobId, "qty", n, "item", Items.pretty(j.item())))
                    .reference("job:" + jobId + ":" + id).actor(p);
            TxResult r = (server ? tx.serverPay(svc.acct(p.toString()), paid) : tx.move(escrow(), svc.acct(p.toString()), paid)).commit().join();
            if (!svc.ok(r)) { st.failDelivery(id); return Result.err(svc.errKey(r.failure())); }
            st.finishDelivery(id, jobId, p, n, svc.targetStock, chest);
            if (server) svc.addJobPaid(paid);
            if (chest) toChest(id, j.project(), j.item(), n, p);
            svc.tutorial.advance(p, 3);
            return new Result(n, paid, null, jobId);
        });
    }

    public CompletableFuture<Result> cancel(UUID p, long jobId) {
        return svc.run(() -> {
            Job j = svc.store.job(jobId);
            if (j == null || !j.status().equals("OPEN")) return Result.err("job-none");
            if (!p.toString().equals(j.poster())) return Result.err("job-not-yours");
            return refund(j, "CANCELLED") ? new Result(j.left(), j.left() * j.pay(), null, jobId) : Result.err("err-other");
        });
    }

    private boolean refund(Job j, String status) {
        if (j.left() > 0) {
            TxResult r = svc.api.transaction(svc.plugin, "market.job", svc.msg.plain("memo-job-refund", "id", j.id())).reference("jobx:" + j.id())
                    .move(escrow(), svc.acct(j.poster()), j.left() * j.pay()).commit().join();
            if (!svc.ok(r)) return false;
        }
        svc.store.jobStatus(j.id(), status);
        return true;
    }

    // ---- project chests ----------------------------------------------------------------------------------------

    private Location chestOf(String project) {
        String spec = chests.get(project);
        if (spec == null) return null;
        String[] a = spec.split(",");
        var world = Bukkit.getWorld(a[0]);
        try { return world == null ? null : new Location(world, Integer.parseInt(a[1].trim()), Integer.parseInt(a[2].trim()), Integer.parseInt(a[3].trim())); }
        catch (NumberFormatException e) { return null; }
    }

    /** Free space for an item in a project's chest, read on the chest's own region thread. */
    private int room(String project, String key) {
        Location loc = chestOf(project);
        if (loc == null) return 0;
        CompletableFuture<Integer> f = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(svc.plugin, loc, t -> {
            if (!(loc.getBlock().getState() instanceof Container c)) { f.complete(0); return; }
            ItemStack one = Items.one(key);
            int room = 0;
            for (ItemStack s : c.getInventory().getStorageContents()) room += s == null || s.getType().isAir() ? one.getMaxStackSize() : s.isSimilar(one) ? one.getMaxStackSize() - s.getAmount() : 0;
            f.complete(room);
        });
        return f.orTimeout(5, TimeUnit.SECONDS).join();
    }

    /**
     * Puts a paid delivery's items in the project chest; whatever doesn't fit (it filled up or was removed meanwhile) goes back to the
     * deliverer's Stash. The delivery stays CHEST until then, so a crash in between sends the items again on start ({@link #recover}).
     * ponytail: the chest and the database don't commit together, so a crash right after the items land can put them in twice; the
     * owner's own chest getting extra beats players losing items.
     */
    private void toChest(long deliveryId, String project, String key, int n, UUID back) {
        Location loc = chestOf(project);
        if (loc == null) { svc.store.deliveryStatus(deliveryId, "DONE"); return; }   // the chest is gone: used up, like a project without one
        Bukkit.getRegionScheduler().run(svc.plugin, loc, t -> {
            int left = n;
            if (loc.getBlock().getState() instanceof Container c)
                left = c.getInventory().addItem(Items.stacks(key, n)).values().stream().mapToInt(ItemStack::getAmount).sum();
            int rest = left;
            svc.run(() -> { svc.store.tx(() -> { if (rest > 0) svc.store.deposit(back, key, rest); svc.store.deliveryStatus(deliveryId, "DONE"); }); return null; });
        });
    }

    // ---- every day, and after a crash --------------------------------------------------------------------------

    /** On the worker: refund expired player jobs, retire old server jobs, then post project jobs and "the town needs" jobs. */
    void onDay(long today) {
        long now = svc.now();
        for (Job j : svc.store.jobs("SELECT * FROM jobs WHERE status='OPEN' AND poster IS NOT NULL AND expires<=?", today)) refund(j, "EXPIRED");
        for (Job j : svc.store.jobs("SELECT * FROM jobs WHERE status='OPEN' AND poster IS NULL AND expires>0 AND expires<=?", today)) svc.store.jobStatus(j.id(), "EXPIRED");
        loadProjects();
        for (var e : needs().entrySet())   // owner projects come first
            for (var need : e.getValue().entrySet()) {
                String project = e.getKey(), item = need.getKey();
                if (!svc.base.containsKey(item) || svc.store.projectJobExists(project, item)) continue;   // we can only price items the server trades
                svc.store.insertJob(null, item, need.getValue(), serverPay(item, premiums.getOrDefault(project, premium)), project, now, 0, "OPEN");
            }
        // then items the server holds more of than its target, or whose price fell; what players bring for these uses up the server's extra
        for (String item : svc.base.keySet()) {
            if (svc.store.serverJobOpen(item)) continue;
            long stock = svc.store.stock(item);
            boolean surplus = stock > svc.targetStock, fell = svc.trend.getOrDefault(item, 1.0) < 0.9;
            if (!surplus && !fell) continue;
            int qty = (int) Math.min(svc.dailyBuyCap, Math.max(64, surplus ? stock - svc.targetStock : svc.targetStock / 4));
            svc.store.insertJob(null, item, qty, serverPay(item, premium), null, now, today + 3, "OPEN");
        }
    }

    private long serverPay(String item, double prem) { return Math.max(1, Math.round(svc.ref(item) * (1 + prem))); }

    private Map<String, Map<String, Integer>> needs() {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(projectsFile());
        for (String name : y.getKeys(false)) {
            var sec = y.getConfigurationSection(name + ".needs");
            if (sec == null) continue;
            Map<String, Integer> m = new HashMap<>();
            for (String raw : sec.getKeys(false)) { String key = Items.parse(raw); if (key != null) m.put(key, sec.getInt(raw)); }
            out.put(name, m);
        }
        return out;
    }

    private void loadProjects() {
        chests.clear();
        premiums.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(projectsFile());
        for (String name : y.getKeys(false)) {
            String to = y.getString(name + ".deliver-to", "");
            if (to.startsWith("chest@")) chests.put(name, to.substring(6));
            if (y.contains(name + ".pay-premium")) premiums.put(name, y.getDouble(name + ".pay-premium"));
        }
    }

    /** Finishes or undoes jobs and deliveries that were in flight at a crash. */
    void recover() {
        loadProjects();
        for (Job j : svc.store.jobs("SELECT * FROM jobs WHERE status='PENDING'"))
            svc.store.jobStatus(j.id(), svc.api.findByReference(svc.plugin, "jobpost:" + j.id()).join().isPresent() ? "OPEN" : "CANCELLED");
        for (var d : svc.store.deliveries("PENDING")) {
            if (!svc.api.findByReference(svc.plugin, "job:" + d.jobId() + ":" + d.id()).join().isPresent()) { svc.store.failDelivery(d.id()); continue; }
            Job j = svc.store.job(d.jobId());
            svc.store.finishDelivery(d.id(), d.jobId(), d.player(), d.qty(), svc.targetStock, j.project() != null);
        }
        for (var d : svc.store.deliveries("CHEST")) {   // paid, but the items never reached the project chest
            Job j = svc.store.job(d.jobId());
            toChest(d.id(), j.project(), j.item(), d.qty(), d.player());
        }
    }
}
