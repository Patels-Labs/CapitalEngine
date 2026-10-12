package com.patelheet.capitalengine.core.ledger;

import com.google.gson.Gson;
import com.patelheet.capitalengine.api.AccountKind;
import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.core.ledger.Item.Barrier;
import com.patelheet.capitalengine.core.ledger.Item.NewAccount;
import com.patelheet.capitalengine.core.ledger.Item.PendingTx;
import com.patelheet.capitalengine.core.storage.Database;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Single writer thread with group commit. A failed batch is retried with backoff (0.5s, 1s, 2s, capped at 10s); if the
 * queue backs up (more than 5,000 pending) or the database stays down for 30s the economy is paused until it recovers.
 * Whatever is still unwritten at shutdown goes to {@code spill.jsonl} and is replayed first on the next start.
 */
public final class PersistQueue {
    private static final int MAX_BATCH = 500;
    private static final Gson GSON = new Gson();

    private final Database db;
    private final JournalRepo repo;
    private final Path spill;
    private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final Map<Long, TxRecord> pending = new ConcurrentHashMap<>();
    private volatile Consumer<Boolean> pauseHook = p -> {};
    private volatile Consumer<TxRecord> committed = r -> {};
    private volatile Consumer<String> critical = m -> {};
    private volatile List<Item> inFlight = List.of();
    private volatile boolean stopping, paused;
    private volatile long stopDeadlineNanos;
    private volatile Thread writer;
    private volatile int pauseBacklog = 5000;
    private volatile long pauseAfterNanos = TimeUnit.SECONDS.toNanos(30), firstRetryMillis = 500;

    public PersistQueue(Database db, JournalRepo repo, Path spill) { this.db = db; this.repo = repo; this.spill = spill; }

    /** Tests only: shrink the pause thresholds and retry delay so failure handling can be exercised quickly. */
    public void tune(int backlog, long pauseAfterMillis, long firstRetryMillis) {
        this.pauseBacklog = backlog; this.pauseAfterNanos = TimeUnit.MILLISECONDS.toNanos(pauseAfterMillis); this.firstRetryMillis = firstRetryMillis;
    }

    public boolean paused() { return paused; }

    public void onPause(Consumer<Boolean> hook) { pauseHook = hook; }
    public void onCommitted(Consumer<TxRecord> hook) { committed = hook; }
    public void onCritical(Consumer<String> hook) { critical = hook; }
    public int size() { return queue.size(); }

    /** A not-yet-persisted transaction, if still in flight. */
    public Optional<TxRecord> pending(long txId) { return Optional.ofNullable(pending.get(txId)); }

    public void add(Item item) {
        if (item instanceof PendingTx t) pending.put(t.rec().txId(), t.rec());
        queue.add(item);
        if (!paused && queue.size() > pauseBacklog) pause(true, "The ledger write queue is backed up (" + queue.size() + " pending).");
    }

    public void start() {
        writer = new Thread(this::run, "CapitalEngine-writer");
        writer.start();
    }

    // ---- writer -----------------------------------------------------------------------------------------------

    private void run() {
        try {
            while (true) {
                Item first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) { if (stopping) return; continue; }
                List<Item> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                inFlight = batch;
                if (!writeWithRetry(batch)) return;   // shutdown while the database was down: leave inFlight for the spill
                inFlight = List.of();
                finish(batch);
                if (paused && queue.size() < pauseBacklog / 2) pause(false, "The ledger caught up.");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean writeWithRetry(List<Item> batch) throws InterruptedException {
        long delay = firstRetryMillis, failingSince = 0;
        while (true) {
            try {
                write(batch);
                return true;
            } catch (Exception e) {
                if (failingSince == 0) failingSince = System.nanoTime();
                if (!paused && System.nanoTime() - failingSince > pauseAfterNanos) pause(true, "The ledger database isn't answering: " + e.getMessage());
                if (stopping && System.nanoTime() > stopDeadlineNanos) return false;
                Thread.sleep(delay);
                delay = Math.min(delay * 2, 10_000);
            }
        }
    }

    private void write(List<Item> batch) throws Exception {
        try (Connection c = db.conn()) { repo.write(c, batch); }
    }

    private void finish(List<Item> batch) {
        for (Item it : batch) {
            if (it instanceof PendingTx t) {
                pending.remove(t.rec().txId());
                t.done().complete(t.result());
                try { committed.accept(t.rec()); } catch (RuntimeException ignored) { /* listeners must not stop the writer */ }
            } else if (it instanceof Barrier b) b.done().complete(null);
        }
    }

    private void pause(boolean on, String why) {
        paused = on;
        pauseHook.accept(on);
        critical.accept(on ? why : "Back to normal: " + why);
    }

    /** Completes once everything queued before this call is in the database. */
    public java.util.concurrent.CompletableFuture<Void> barrier() {
        var f = new java.util.concurrent.CompletableFuture<Void>();
        queue.add(new Barrier(f));
        return f;
    }

    // ---- shutdown and spill -----------------------------------------------------------------------------------

    /** Drains for up to {@code timeoutMs}, then spills whatever is left. */
    public void shutdown(long timeoutMs) throws IOException {
        stopDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        stopping = true;
        Thread w = writer;
        if (w != null) {
            try {
                w.join(timeoutMs + 500);
                if (w.isAlive()) { w.interrupt(); w.join(1000); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        List<Item> rest = new ArrayList<>(inFlight);
        queue.drainTo(rest);
        if (!rest.isEmpty()) spill(rest);
        for (Item it : rest) {
            if (it instanceof PendingTx t) t.done().complete(t.result());   // safe in the spill file
            else if (it instanceof Barrier b) b.done().complete(null);
        }
    }

    private void spill(List<Item> items) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Item it : items) {
            S s = new S();
            if (it instanceof NewAccount n) {
                var a = n.acct();
                s.k = "a"; s.id = a.id().toString(); s.kind = a.kind().name(); s.plugin = a.plugin(); s.key = a.key(); s.name = a.name(); s.ts = n.createdAt();
            } else if (it instanceof PendingTx t) {
                TxRecord r = t.rec();
                s.k = "t"; s.tx = r.txId(); s.ts = r.time().toEpochMilli(); s.type = r.type(); s.memo = r.memo(); s.src = r.sourcePlugin();
                s.actor = r.actor() == null ? null : r.actor().toString(); s.ref = r.reference(); s.rev = r.reversesTxId(); s.day = t.day(); s.disp = t.plugin();
                s.c = t.counters();
                s.post = r.postings().stream().map(p -> p.accountId() + "|" + p.amount()).toList();
                s.bal = t.balances().entrySet().stream().map(e -> e.getKey() + "|" + e.getValue()).toList();
            } else continue;
            lines.add(GSON.toJson(s));
        }
        Files.write(spill, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel ch = FileChannel.open(spill, StandardOpenOption.WRITE)) { ch.force(true); }
    }

    /** Writes a leftover spill file to the database (in file order, which is txId order) and deletes it. */
    public void replaySpill() throws Exception {
        if (!Files.exists(spill)) return;
        List<Item> items = new ArrayList<>();
        for (String line : Files.readAllLines(spill, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            S s = GSON.fromJson(line, S.class);
            if ("a".equals(s.k)) {
                items.add(new NewAccount(new Acct(UUID.fromString(s.id), AccountKind.valueOf(s.kind), s.plugin, s.key, s.name), s.ts));
            } else {
                List<Posting> post = new ArrayList<>();
                for (String p : s.post) { String[] x = p.split("\\|"); post.add(new Posting(UUID.fromString(x[0]), Long.parseLong(x[1]))); }
                Map<UUID, Long> bal = new HashMap<>();
                for (String b : s.bal) { String[] x = b.split("\\|"); bal.put(UUID.fromString(x[0]), Long.parseLong(x[1])); }
                TxRecord r = new TxRecord(s.tx, Instant.ofEpochMilli(s.ts), s.type, s.memo, s.src, s.actor == null ? null : UUID.fromString(s.actor), s.ref, s.rev, post);
                items.add(new PendingTx(r, s.day, s.disp, s.c, bal, null, null));
            }
        }
        for (int i = 0; i < items.size(); i += MAX_BATCH) write(items.subList(i, Math.min(items.size(), i + MAX_BATCH)));
        Files.delete(spill);
    }

    /** One spill line; flat on purpose so Gson needs no adapters. */
    private static final class S {
        String k, id, kind, plugin, key, name, type, memo, src, actor, ref, disp;
        long ts, tx, day;
        Long rev;
        long[] c;
        List<String> post, bal;
    }
}
