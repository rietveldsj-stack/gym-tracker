package com.gymtracker.exercise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "exercise")
public class Exercise {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "muscle_group", nullable = false, length = 20)
    private MuscleGroup muscleGroup;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Exercise() {
    }

    public Exercise(UUID id, UUID userId, String name, MuscleGroup muscleGroup, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.muscleGroup = muscleGroup;
        this.createdAt = createdAt;
    }

    public void update(String name, MuscleGroup muscleGroup) {
        this.name = name;
        this.muscleGroup = muscleGroup;
    }

    public void archive() {
        this.archived = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isOwnedBy(UUID userId) {
        return this.userId.equals(userId);
    }

    public String getName() {
        return name;
    }

    public MuscleGroup getMuscleGroup() {
        return muscleGroup;
    }

    public boolean isArchived() {
        return archived;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
