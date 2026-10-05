package com.gymtracker.workout;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutSessionRepository extends JpaRepository<WorkoutSession, UUID> {

    Optional<WorkoutSession> findFirstByEndedAtIsNull();

    List<WorkoutSession> findByEndedAtIsNotNullOrderByStartedAtDesc();

    List<WorkoutSession> findByEndedAtIsNotNullAndDateBetween(LocalDate from, LocalDate to);
}
