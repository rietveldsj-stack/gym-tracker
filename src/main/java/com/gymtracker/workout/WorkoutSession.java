package com.gymtracker.workout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "workout_session")
public class WorkoutSession {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "session_date", nullable = false)
    private LocalDate date;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected WorkoutSession() {
    }

    public WorkoutSession(UUID id, UUID userId, LocalDate date, Instant startedAt) {
        this.id = id;
        this.userId = userId;
        this.date = date;
        this.startedAt = startedAt;
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }

    public void end(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
