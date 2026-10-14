package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.api.TxResult;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The 5-step tutorial (market/Plan.md §5.11): /money, Quick Sell, deliver to a job, /economy, /bank (only if CapitalBank is
 * there). Each step pays once, in order: the reference {@code tut:<uuid>:<step>} stops a repeat from ever paying twice.
 */
public final class Tutorial implements Listener {
    private final MarketService svc;
    private final boolean enabled;
    private final long reward;
    private final Map<UUID, Integer> steps = new ConcurrentHashMap<>();   // players still doing it -> their step
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    Tutorial(MarketService svc, FileConfiguration cfg) {
        this.svc = svc;
        enabled = cfg.getBoolean("tutorial.enabled", true);
        reward = svc.api.currency().parse(cfg.getString("tutorial.reward", "20"));
    }

    private int last() { return Bukkit.getPluginManager().isPluginEnabled("CapitalBank") ? 5 : 4; }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        UUID id = e.getPlayer().getUniqueId();
        svc.run(() -> svc.store.tutorialStep(id)).thenAccept(step -> { if (step <= last()) { steps.put(id, step); show(id, step); } });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        steps.remove(e.getPlayer().getUniqueId());
        BossBar b = bars.remove(e.getPlayer().getUniqueId());
        if (b != null) e.getPlayer().hideBossBar(b);
    }

    @EventHandler public void onCommand(PlayerCommandPreprocessEvent e) {
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (cmd.equals("money") || cmd.equals("bal") || cmd.equals("balance")) advance(e.getPlayer().getUniqueId(), 1);
        else if (cmd.equals("economy")) advance(e.getPlayer().getUniqueId(), 4);
        else if (cmd.equals("bank")) advance(e.getPlayer().getUniqueId(), 5);
    }

    /** Called when a player does what step {@code step} asks. Anything but their current step is ignored. */
    public void advance(UUID p, int step) {
        Integer cur = steps.get(p);
        if (!enabled || cur == null || cur != step) return;
        svc.run(() -> {
            if (svc.store.tutorialStep(p) != step) return null;
            TxResult r = svc.api.transaction(svc.plugin, "market.tutorial", svc.msg.plain("memo-tutorial", "step", step)).reference("tut:" + p + ":" + step).actor(p)
                    .serverPay(svc.acct(p.toString()), reward).commit().join();
            if (!svc.ok(r)) return null;   // limits: they can repeat the step later
            svc.store.setTutorialStep(p, step + 1);
            return step + 1;
        }).thenAccept(next -> {
            if (next == null) return;
            if (next > last()) steps.remove(p); else steps.put(p, next);
            Player pl = Bukkit.getPlayer(p);
            if (pl != null) pl.getScheduler().run(svc.plugin, t -> {
                svc.msg.send(pl, "tutorial-reward", "total", svc.api.currency().format(reward));
                if (next > last()) { svc.msg.send(pl, "tutorial-done"); BossBar b = bars.remove(p); if (b != null) pl.hideBossBar(b); } else show(p, next);
            }, null);
        });
    }

    /** {@code /market tutorial skip} */
    public void skip(UUID p) {
        steps.remove(p);
        svc.run(() -> { svc.store.tutorialStep(p); svc.store.setTutorialStep(p, 99); return null; });
        Player pl = Bukkit.getPlayer(p);
        BossBar b = bars.remove(p);
        if (pl != null && b != null) pl.hideBossBar(b);
    }

    private void show(UUID p, int step) {
        Player pl = Bukkit.getPlayer(p);
        if (pl == null) return;
        pl.getScheduler().run(svc.plugin, t -> {
            var title = svc.msg.get("tutorial-step-" + step);
            float progress = (step - 1) / (float) last();
            BossBar b = bars.get(p);
            if (b == null) { b = BossBar.bossBar(title, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS); bars.put(p, b); pl.showBossBar(b); }
            else { b.name(title); b.progress(progress); }
        }, null);
    }
}
