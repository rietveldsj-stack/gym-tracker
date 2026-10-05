package com.gymtracker.push;

import java.time.Clock;
import java.util.List;
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

    public void save(String endpoint, String p256dh, String auth) {
        repository.findById(endpoint).ifPresentOrElse(
                existing -> existing.updateKeys(p256dh, auth),
                () -> repository.save(new PushSubscription(endpoint, p256dh, auth, clock.instant())));
    }

    @Transactional(readOnly = true)
    public List<PushSubscription> all() {
        return repository.findAll();
    }

    public void remove(String endpoint) {
        repository.deleteById(endpoint);
    }
}
