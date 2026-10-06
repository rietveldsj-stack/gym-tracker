package com.gymtracker.push;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;
    private final Clock clock;

    public PushSubscriptionService(PushSubscriptionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void save(UUID userId, String endpoint, String p256dh, String auth) {
        repository.findById(endpoint).ifPresentOrElse(
                existing -> existing.assignTo(userId, p256dh, auth),
                () -> repository.save(new PushSubscription(endpoint, userId, p256dh, auth, clock.instant())));
    }

    @Transactional(readOnly = true)
    public List<PushSubscription> forUser(UUID userId) {
        return repository.findByUserId(userId);
    }

    public void removeForUser(UUID userId, String endpoint) {
        repository.findById(endpoint).filter(s -> s.getUserId().equals(userId)).ifPresent(repository::delete);
    }

    public void remove(String endpoint) {
        repository.deleteById(endpoint);
    }
}
