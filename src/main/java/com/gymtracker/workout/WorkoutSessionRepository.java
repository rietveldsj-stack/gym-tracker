package com.gymtracker.workout;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutSessionRepository extends JpaRepository<WorkoutSession, UUID> {

    Optional<WorkoutSession> findByIdAndUserId(UUID id, UUID userId);

    Optional<WorkoutSession> findFirstByUserIdAndEndedAtIsNull(UUID userId);

    List<WorkoutSession> findByUserIdAndEndedAtIsNotNullOrderByStartedAtDesc(UUID userId);

    List<WorkoutSession> findByUserIdAndEndedAtIsNotNullAndDateBetween(UUID userId, LocalDate from, LocalDate to);
}
