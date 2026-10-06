package com.gymtracker.push;

import com.gymtracker.common.BadRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/** Holds each account's pending "rest is over" alert in memory; a restart loses them, which the spec accepts. */
@Service
public class RestTimerService {

    static final String PAYLOAD = "{\"title\":\"Rest's over\",\"body\":\"Time for your next set 💪\"}";
    private static final Duration MAX_AHEAD = Duration.ofHours(1);

    private final TaskScheduler scheduler;
    private final PushSubscriptionService subscriptions;
    private final PushSender sender;
    private final Clock clock;

    // One pending alert per account. A generation per account makes an alert that was replaced or cancelled after
    // it already started firing a no-op.
    private final Map<UUID, ScheduledFuture<?>> pending = new HashMap<>();
    private final Map<UUID, Long> generations = new HashMap<>();

    public RestTimerService(TaskScheduler scheduler, PushSubscriptionService subscriptions, PushSender sender,
                            Clock clock) {
        this.scheduler = scheduler;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.clock = clock;
    }

    public synchronized void schedule(UUID userId, Instant endsAt) {
        Instant now = clock.instant();
        if (!endsAt.isAfter(now) || endsAt.isAfter(now.plus(MAX_AHEAD))) {
            throw new BadRequestException("endsAt must be in the future and at most 1 hour away");
        }
        cancel(userId);
        long current = generations.merge(userId, 1L, Long::sum);
        pending.put(userId, scheduler.schedule(() -> fire(userId, current), endsAt));
    }

    public synchronized void cancel(UUID userId) {
        ScheduledFuture<?> future = pending.remove(userId);
        if (future != null) {
            future.cancel(false);
        }
        generations.merge(userId, 1L, Long::sum);
    }

    public synchronized void cancelAll() {
        pending.values().forEach(future -> future.cancel(false));
        pending.clear();
        generations.replaceAll((userId, generation) -> generation + 1);
    }

    private void fire(UUID userId, long firedGeneration) {
        synchronized (this) {
            if (generations.getOrDefault(userId, 0L) != firedGeneration) {
                return;
            }
            pending.remove(userId);
        }
        for (PushSubscription subscription : subscriptions.forUser(userId)) {
            if (sender.send(subscription, PAYLOAD) == PushSender.SendResult.GONE) {
                subscriptions.remove(subscription.getEndpoint());
            }
        }
    }
}
