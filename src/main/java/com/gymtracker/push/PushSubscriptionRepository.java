package com.gymtracker.push;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, String> {

    List<PushSubscription> findByUserId(UUID userId);
}
