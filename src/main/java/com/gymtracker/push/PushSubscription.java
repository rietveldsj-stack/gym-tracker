package com.gymtracker.push;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    @Id
    @Column(length = 2048)
    private String endpoint;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String p256dh;

    @Column(nullable = false)
    private String auth;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PushSubscription() {
    }

    public PushSubscription(String endpoint, UUID userId, String p256dh, String auth, Instant createdAt) {
        this.endpoint = endpoint;
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
        this.createdAt = createdAt;
    }

    /** The same phone endpoint can be re-registered by another account (shared phone): it then moves there. */
    public void assignTo(UUID userId, String p256dh, String auth) {
        this.userId = userId;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getP256dh() {
        return p256dh;
    }

    public String getAuth() {
        return auth;
    }
}
