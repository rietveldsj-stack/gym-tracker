package com.gymtracker.push;

import com.gymtracker.common.BadRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/** Holds the one pending "rest is over" alert in memory; a restart loses it, which the spec accepts. */
@Service
public class RestTimerService {

    static final String PAYLOAD = "{\"title\":\"Rest's over\",\"body\":\"Time for your next set 💪\"}";
    private static final Duration MAX_AHEAD = Duration.ofHours(1);

    private final TaskScheduler scheduler;
    private final PushSubscriptionService subscriptions;
    private final PushSender sender;
    private final Clock clock;

    private ScheduledFuture<?> pending;
    private long generation;

    public RestTimerService(TaskScheduler scheduler, PushSubscriptionService subscriptions, PushSender sender,
                            Clock clock) {
        this.scheduler = scheduler;
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.clock = clock;
    }

    public synchronized void schedule(Instant endsAt) {
        Instant now = clock.instant();
        if (!endsAt.isAfter(now) || endsAt.isAfter(now.plus(MAX_AHEAD))) {
            throw new BadRequestException("endsAt must be in the future and at most 1 hour away");
        }
        cancel();
        long current = ++generation;
        pending = scheduler.schedule(() -> fire(current), endsAt);
    }

    public synchronized void cancel() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    private void fire(long firedGeneration) {
        synchronized (this) {
            if (firedGeneration != generation) {
                return;
            }
            pending = null;
        }
        for (PushSubscription subscription : subscriptions.all()) {
            if (sender.send(subscription, PAYLOAD) == PushSender.SendResult.GONE) {
                subscriptions.remove(subscription.getEndpoint());
            }
        }
    }
}
