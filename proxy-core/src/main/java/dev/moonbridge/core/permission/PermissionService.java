package dev.moonbridge.core.permission;

import dev.moonbridge.api.PlayerIdentity;
import dev.moonbridge.api.PlayerView;
import dev.moonbridge.api.permission.PermissionContext;
import dev.moonbridge.api.permission.PermissionProvider;
import dev.moonbridge.api.permission.PermissionResult;
import dev.moonbridge.api.permission.PermissionSubject;
import dev.moonbridge.api.permission.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns permission subjects for exact connections, including connections not yet published as players. */
public final class PermissionService implements Permissions, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionService.class);
    private static final int MAX_SUBJECTS = 4096;
    private final Duration timeout;
    private final ConcurrentHashMap<PlayerIdentity, Entry> entries = new ConcurrentHashMap<>();
    // Timed-out providers may still complete later. Retain their permit until they actually finish.
    private final Semaphore pendingLoads = new Semaphore(1024);
    private final ThreadPoolExecutor loaders = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("moonbridge-permission-load-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    // One cleanup task per acquired subject, bounded by admitted subjects rather than a lossy event queue.
    private final ExecutorService cleanup = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("moonbridge-permission-release-", 0).factory());
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("moonbridge-permission-timeout-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile PermissionProvider provider;
    private boolean started;

    public PermissionService(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(3)) > 0) {
            throw new IllegalArgumentException("permission timeout must be positive and within three minutes");
        }
        timer.setRemoveOnCancelPolicy(true);
    }

    /** Called once during plugin startup, before any connection is admitted. */
    public synchronized void configure(PermissionProvider selected) {
        if (closed.get() || started || provider != null) throw new IllegalStateException("Permission provider is already frozen");
        provider = Objects.requireNonNull(selected, "provider");
    }

    /** Invokes the provider away from network event loops; the returned stage has a bounded deadline. */
    public synchronized CompletionStage<Void> prepare(PlayerView player) {
        Objects.requireNonNull(player, "player");
        started = true;
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("Permissions are closed"));
        PermissionProvider selected = provider;
        if (selected == null) return CompletableFuture.completedFuture(null);
        Entry existing = entries.get(player.identity());
        if (existing != null) return existing.ready.minimalCompletionStage();
        if (entries.size() >= MAX_SUBJECTS) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Permission subject capacity exceeded"));
        }
        if (!pendingLoads.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Permission loading capacity exceeded"));
        }
        Entry entry = new Entry(player);
        entries.put(player.identity(), entry);
        try {
            entry.deadline = timer.schedule(() -> {
                synchronized (entry) {
                    if (entry.subject == null) fail(entry, new TimeoutException("Permission loading timed out"));
                }
            },
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            loaders.execute(() -> {
                if (entry.ended.get()) { finishLoad(entry); return; }
                try {
                    CompletionStage<PermissionSubject> opening = Objects.requireNonNull(selected.open(player),
                            "permission provider returned a null stage");
                    opening.whenComplete((subject, failure) -> {
                        finishLoad(entry);
                        loaded(entry, subject, failure);
                    });
                } catch (Throwable failure) {
                    finishLoad(entry);
                    fail(entry, failure);
                }
            });
        } catch (RejectedExecutionException overloaded) {
            finishLoad(entry);
            fail(entry, overloaded);
        }
        // Callers cannot cancel the provider's future: a late subject must still be released.
        return entry.ready.minimalCompletionStage();
    }

    private void finishLoad(Entry entry) {
        if (entry.loadFinished.compareAndSet(false, true)) pendingLoads.release();
    }

    private void loaded(Entry entry, PermissionSubject subject, Throwable failure) {
        if (failure != null || subject == null) {
            if (subject != null) dispose(subject);
            fail(entry, failure == null ? new NullPointerException("permission provider returned a null subject") : failure);
            return;
        }
        synchronized (entry) {
            if (entry.ended.get() || closed.get() || entries.get(entry.player.identity()) != entry) {
                dispose(subject);
                return;
            }
            entry.subject = subject;
            if (entry.deadline != null) entry.deadline.cancel(false);
            entry.ready.complete(null);
        }
    }

    /** Updates the authoritative connection snapshot before notifying business plugins of a committed transfer. */
    public void update(PlayerView player) {
        Objects.requireNonNull(player, "player");
        Entry entry = entries.get(player.identity());
        if (entry == null || closed.get()) return;
        synchronized (entry) {
            if (entry.ended.get()) return;
            entry.player = player;
            if (entry.subject != null) {
                try {
                    entry.subject.update(player);
                } catch (Throwable failure) {
                    LOGGER.warn("Permission subject update failed; denying subsequent checks for {}", player.identity(), failure);
                    fail(entry, failure);
                }
            }
        }
    }

    @Override public PermissionResult check(PlayerIdentity identity, String node) {
        return check(identity, node, PermissionContext.empty());
    }

    @Override public PermissionResult check(PlayerIdentity identity, String node, PermissionContext context) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(context, "context");
        if (node.isBlank() || node.length() > 256 || node.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("permission node must contain 1 to 256 non-whitespace characters");
        }
        if (closed.get()) return PermissionResult.UNAVAILABLE;
        Entry entry = entries.get(identity);
        if (entry == null) return PermissionResult.UNAVAILABLE;
        synchronized (entry) {
            if (entry.ended.get() || entry.subject == null || closed.get()) return PermissionResult.UNAVAILABLE;
            var values = new HashMap<String, java.util.Set<String>>();
            entry.player.currentServer().ifPresent(server -> values.put("backend", java.util.Set.of(server)));
            // Explicit query context permits testing a proposed destination before a transfer.
            values.putAll(context.values());
            try {
                return switch (Objects.requireNonNull(entry.subject.check(node.toLowerCase(Locale.ROOT),
                        new PermissionContext(values)), "permission decision")) {
                    case ALLOW -> PermissionResult.ALLOW;
                    case DENY -> PermissionResult.DENY;
                    case UNDEFINED -> PermissionResult.UNDEFINED;
                };
            } catch (Throwable failure) {
                LOGGER.warn("Permission query failed; denying subsequent checks for {}", identity, failure);
                fail(entry, failure);
                return PermissionResult.UNAVAILABLE;
            }
        }
    }

    /** Called for every claimed identity, including failed admission, login and routing. */
    public void release(PlayerIdentity identity) {
        Entry entry = entries.get(Objects.requireNonNull(identity, "identity"));
        if (entry != null) fail(entry, new IllegalStateException("Player connection ended"));
    }

    private void fail(Entry entry, Throwable failure) {
        synchronized (entry) {
            if (!entry.ended.compareAndSet(false, true)) return;
            entries.remove(entry.player.identity(), entry);
            if (entry.deadline != null) entry.deadline.cancel(false);
            entry.ready.completeExceptionally(failure);
            if (entry.subject != null) {
                PermissionSubject subject = entry.subject;
                entry.subject = null;
                dispose(subject);
            }
        }
    }

    private void dispose(PermissionSubject subject) {
        Runnable close = () -> {
            try { subject.close(); }
            catch (Throwable failure) { LOGGER.warn("Permission subject cleanup failed", failure); }
        };
        try { cleanup.execute(close); }
        catch (RejectedExecutionException stopped) {
            // A provider may finish after shutdown. Its resource still has exactly one owner.
            Thread.ofVirtual().name("moonbridge-permission-late-release").start(close);
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
        }
        entries.values().forEach(entry -> fail(entry, new IllegalStateException("Permissions are closed")));
        loaders.shutdownNow();
        timer.shutdownNow();
        cleanup.shutdown();
        try {
            if (!cleanup.awaitTermination(3, TimeUnit.SECONDS)) cleanup.shutdownNow();
        } catch (InterruptedException interrupted) {
            cleanup.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class Entry {
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicBoolean loadFinished = new AtomicBoolean();
        private volatile PlayerView player;
        private PermissionSubject subject;
        private volatile ScheduledFuture<?> deadline;

        private Entry(PlayerView player) { this.player = player; }
    }
}
