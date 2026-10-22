package dev.moonbridge.core.plugin;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.event.ConnectionAdmissionEvent;
import dev.moonbridge.api.event.Event;
import dev.moonbridge.api.event.EventListener;
import dev.moonbridge.api.event.EventSubscription;
import dev.moonbridge.api.event.PlayerAdmissionEvent;
import dev.moonbridge.api.event.PlayerDisconnectedEvent;
import dev.moonbridge.api.event.ServerConnectedEvent;
import dev.moonbridge.api.event.TransferDecision;
import dev.moonbridge.api.event.TransferPreparingEvent;
import dev.moonbridge.api.Plugin;
import dev.moonbridge.core.event.PreparedTransfer;
import dev.moonbridge.core.event.TransferPreparation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Event subscriptions and dispatch. Listeners register while the host is loading, are frozen when it
 * enables, and run on dedicated bounded dispatchers so plugin code never blocks the caller.
 */
final class EventRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventRouter.class);
    private static final int MAX_PENDING_TRANSFER_EVENTS = 128;
    private static final PreparedTransfer ALLOWED_TRANSFER = new PreparedTransfer() {
        @Override public boolean requiresSourceRelease() { return false; }
        @Override public CompletionStage<Void> sourceClosed() {
            return CompletableFuture.completedFuture(null);
        }
    };

    private final HostLifecycle lifecycle;
    private final Object lock;
    private final Duration eventTimeout;
    private final ScheduledThreadPoolExecutor timer;
    private final AsyncEventDispatcher admissionEvents;
    private final AsyncEventDispatcher notificationEvents;
    private final AsyncEventDispatcher.EventPolicy<TransferDecision> transferPolicy;
    private final AsyncEventDispatcher.EventPolicy<AccessDecision> admissionPolicy;
    private final AsyncEventDispatcher.EventPolicy<Void> notificationPolicy;
    private final AtomicLong notificationDrops = new AtomicLong();
    private final List<EventRegistration<?, ?>> eventRegistrations = new ArrayList<>();
    private ScheduledFuture<?> notificationDropReporter;
    private volatile Map<Class<?>, List<EventRegistration<?, ?>>> eventListeners = Map.of();
    private volatile AsyncEventDispatcher transferEvents;

    EventRouter(HostLifecycle lifecycle, Object lock, Duration eventTimeout, ScheduledThreadPoolExecutor timer,
                int accessThreads, int accessQueueCapacity, int maxPendingAccess) {
        this.lifecycle = lifecycle;
        this.lock = lock;
        this.eventTimeout = eventTimeout;
        this.timer = timer;
        this.admissionPolicy = new AsyncEventDispatcher.EventPolicy<>(AccessDecision::allow,
                decision -> decision instanceof AccessDecision.Allowed || decision instanceof AccessDecision.Denied,
                decision -> decision instanceof AccessDecision.Denied, false, (event, failure) -> { },
                maxPendingAccess, false);
        this.notificationPolicy = new AsyncEventDispatcher.EventPolicy<>(() -> null, ignored -> true,
                ignored -> false, true, (event, failure) -> LOGGER.warn("Plugin event listener failed for {}",
                event.getClass().getName(), failure), 129, true);
        this.transferPolicy = new AsyncEventDispatcher.EventPolicy<>(TransferDecision::allow,
                decision -> decision instanceof TransferDecision.Allowed
                        || decision instanceof TransferDecision.Denied,
                decision -> decision instanceof TransferDecision.Denied, false, (event, failure) -> { },
                MAX_PENDING_TRANSFER_EVENTS, false);
        this.admissionEvents = new AsyncEventDispatcher(eventTimeout, accessThreads, accessQueueCapacity,
                timer, PluginThreads.named("moonbridge-plugin-admission"));
        this.notificationEvents = new AsyncEventDispatcher(eventTimeout, 1, 128,
                timer, PluginThreads.named("moonbridge-plugin-notification"));
    }

    /** Freezes subscriptions and starts the transfer dispatcher if anyone listens; call before enabling. */
    void freeze() {
        freezeEventRegistrations();
        if (eventListeners.getOrDefault(TransferPreparingEvent.class, List.of()).stream()
                .anyMatch(EventRegistration::isActive)) {
            transferEvents = new AsyncEventDispatcher(eventTimeout, 2, 128,
                    timer, PluginThreads.named("moonbridge-plugin-transfer"));
        }
    }

    /** Starts drop reporting when notification listeners exist; call once the host is enabled. */
    void startReporting() {
        if (eventListeners.containsKey(ServerConnectedEvent.class)
                || eventListeners.containsKey(PlayerDisconnectedEvent.class)) {
            notificationDropReporter = timer.scheduleAtFixedRate(this::logNotificationDrops,
                    1, 1, TimeUnit.MINUTES);
        }
    }

    /** Settles pending chains before listeners are revoked; otherwise a queued chain would finish with allow(). */
    void closeDispatchers() {
        admissionEvents.close();
        notificationEvents.close();
        if (transferEvents != null) transferEvents.close();
    }

    void stopReporting() {
        if (notificationDropReporter != null) notificationDropReporter.cancel(false);
        logNotificationDrops();
    }

    void clear() {
        eventListeners = Map.of();
        eventRegistrations.clear();
    }

    boolean hasSubscribers(Class<?> eventType) {
        Objects.requireNonNull(eventType, "eventType");
        for (EventRegistration<?, ?> listener : eventListeners.getOrDefault(eventType, List.of())) {
            if (listener.isActive()) return true;
        }
        return false;
    }

    <R> CompletionStage<R> dispatch(Event<R> event) {
        Objects.requireNonNull(event, "event");
        if (!lifecycle.enabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Plugin host is not enabled"));
        }
        List<EventRegistration<?, ?>> registrations = eventListeners.getOrDefault(event.getClass(), List.of());
        if (event instanceof ConnectionAdmissionEvent || event instanceof PlayerAdmissionEvent) {
            return dispatchWithPolicy(admissionEvents, event, registrations, admissionPolicy);
        }
        if (event instanceof ServerConnectedEvent || event instanceof PlayerDisconnectedEvent) {
            CompletionStage<R> delivery = dispatchWithPolicy(notificationEvents, event, registrations, notificationPolicy);
            delivery.whenComplete((ignored, failure) -> {
                if (failure != null) notificationDrops.incrementAndGet();
            });
            return delivery;
        }
        if (event instanceof TransferPreparingEvent) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Transfer preparation must use a pinned transfer cohort"));
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException(
                "Unsupported event type: " + event.getClass().getName()));
    }

    TransferPreparation selectTransferPreparation() {
        if (!lifecycle.enabled()) return null;
        List<EventRegistration<?, ?>> registered = eventListeners.getOrDefault(TransferPreparingEvent.class, List.of());
        AsyncEventDispatcher dispatcher = transferEvents;
        if (registered.isEmpty() || dispatcher == null) return null;
        List<EventRegistration<?, ?>> cohort = registered.stream().filter(EventRegistration::isActive).toList();
        if (cohort.isEmpty()) return null;
        return event -> prepareTransfer(dispatcher, cohort, event);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CompletionStage<PreparedTransfer> prepareTransfer(AsyncEventDispatcher dispatcher,
            List<EventRegistration<?, ?>> cohort, TransferPreparingEvent event) {
        Objects.requireNonNull(event, "event");
        List<PinnedReleaseCallback> callbacks = new ArrayList<>();
        List<AsyncEventDispatcher.EventHandler<TransferDecision>> handlers = new ArrayList<>(cohort.size());
        for (EventRegistration<?, ?> raw : cohort) {
            EventRegistration<TransferPreparingEvent, TransferDecision> registration = (EventRegistration) raw;
            handlers.add(new AsyncEventDispatcher.EventHandler<>() {
                @Override public boolean active() { return true; }

                @Override public CompletionStage<TransferDecision> handle(Event<?> ignored) {
                    registration.requireActive();
                    CompletionStage<TransferDecision> stage = Objects.requireNonNull(
                            registration.handle(event), "transfer preparation stage");
                    return CancellableStages.map(stage, decision -> {
                        registration.requireActive();
                        Objects.requireNonNull(decision, "transfer decision");
                        if (decision instanceof TransferDecision.Denied) return decision;
                        if (decision instanceof TransferDecision.ReleaseSource release) {
                            callbacks.add(new PinnedReleaseCallback(registration, release.afterSourceClosed()));
                        } else if (!(decision instanceof TransferDecision.Allowed)) {
                            throw new IllegalStateException("Unsupported transfer decision");
                        }
                        return TransferDecision.allow();
                    });
                }
            });
        }
        CompletionStage<TransferDecision> dispatched = dispatcher.dispatch(event, handlers, transferPolicy);
        return CancellableStages.map(dispatched, decision -> {
            if (decision instanceof TransferDecision.Denied) {
                LOGGER.info("Transfer preparation denied for transfer {}", event.context().transferId());
                throw new IllegalStateException("transfer preparation denied");
            }
            List<PinnedReleaseCallback> pinned = List.copyOf(callbacks);
            if (pinned.isEmpty()) return ALLOWED_TRANSFER;
            return new PreparedTransfer() {
                private final AtomicBoolean releaseStarted = new AtomicBoolean();

                @Override public boolean requiresSourceRelease() { return !pinned.isEmpty(); }

                @Override public CompletionStage<Void> sourceClosed() {
                    if (pinned.isEmpty()) return CompletableFuture.completedFuture(null);
                    if (!releaseStarted.compareAndSet(false, true)) {
                        return CompletableFuture.failedFuture(
                            new IllegalStateException("source release callbacks already started"));
                    }
                    List<AsyncEventDispatcher.EventHandler<TransferDecision>> releaseHandlers = pinned.stream()
                            .<AsyncEventDispatcher.EventHandler<TransferDecision>>map(callback ->
                                    new AsyncEventDispatcher.EventHandler<>() {
                                        @Override public boolean active() { return true; }
                                        @Override public CompletionStage<TransferDecision> handle(Event<?> ignored) {
                                            return CancellableStages.map(callback.invoke(),
                                                    nothing -> TransferDecision.allow());
                                        }
                                    }).toList();
                    CompletionStage<TransferDecision> release = dispatcher.dispatch(event, releaseHandlers,
                            transferPolicy);
                    return CancellableStages.map(release, ignored -> {
                        pinned.forEach(PinnedReleaseCallback::requireActive);
                        return null;
                    });
                }
            };
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <R> CompletionStage<R> dispatchWithPolicy(AsyncEventDispatcher dispatcher, Event<R> event,
                                                       List<EventRegistration<?, ?>> registrations,
                                                       AsyncEventDispatcher.EventPolicy<?> policy) {
        return dispatcher.dispatch(event, (List) registrations, (AsyncEventDispatcher.EventPolicy) policy);
    }

    private void freezeEventRegistrations() {
        synchronized (lock) {
        var frozen = new java.util.LinkedHashMap<Class<?>, List<EventRegistration<?, ?>>>();
        for (EventRegistration<?, ?> registration : eventRegistrations) {
            frozen.computeIfAbsent(registration.eventType(), ignored -> new ArrayList<>()).add(registration);
        }
        frozen.replaceAll((ignored, registrations) -> List.copyOf(registrations));
        eventListeners = Map.copyOf(frozen);
        }
    }

    <R, E extends Event<R>> EventSubscription register(
            PluginContextImpl context, Class<E> eventType, EventListener<? super E, R> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        if (eventType != ConnectionAdmissionEvent.class && eventType != PlayerAdmissionEvent.class
                && eventType != ServerConnectedEvent.class && eventType != PlayerDisconnectedEvent.class
                && eventType != TransferPreparingEvent.class)
            throw new IllegalArgumentException("Unsupported event type: " + eventType.getName());
        requireEventRegistrationState();
        synchronized (lock) {
            requireEventRegistrationState();
            synchronized (context) {
                context.requireEventRegistrationOpen();
                EventRegistration<E, R> registration = new EventRegistration<>(context, eventType, listener);
                eventRegistrations.add(registration);
                context.eventSubscriptions.add(registration);
                return registration;
            }
        }
    }

    private void requireEventRegistrationState() {
        if (!lifecycle.loading()) {
            throw new IllegalStateException("Event subscriptions are only allowed during onLoad or onEnable");
        }
    }

    private void logNotificationDrops() {
        long count = notificationDrops.getAndSet(0);
        if (count != 0) {
            LOGGER.warn("Dropped {} plugin notification event(s) after timeout, failure, or queue overflow", count);
        }
    }
}
