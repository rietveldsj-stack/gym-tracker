package com.gymtracker.workout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workout_set")
public class WorkoutSet {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "exercise_id", nullable = false)
    private UUID exerciseId;

    @Column(name = "weight_kg", nullable = false, precision = 6, scale = 2)
    private BigDecimal weightKg;

    @Column(nullable = false)
    private int reps;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SetType type;

    @Column(name = "logged_at", nullable = false)
    private Instant loggedAt;

    protected WorkoutSet() {
    }

    public WorkoutSet(UUID id, UUID sessionId, UUID exerciseId, BigDecimal weightKg, int reps, SetType type,
                      Instant loggedAt) {
        this.id = id;
        this.sessionId = sessionId;
        update(exerciseId, weightKg, reps, type, loggedAt);
    }

    public void update(UUID exerciseId, BigDecimal weightKg, int reps, SetType type, Instant loggedAt) {
        this.exerciseId = exerciseId;
        this.weightKg = weightKg;
        this.reps = reps;
        this.type = type;
        this.loggedAt = loggedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getExerciseId() {
        return exerciseId;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public int getReps() {
        return reps;
    }

    public SetType getType() {
        return type;
    }

    public Instant getLoggedAt() {
        return loggedAt;
    }
}
