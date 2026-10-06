package com.gymtracker.workout;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface WorkoutSetRepository extends JpaRepository<WorkoutSet, UUID> {

    List<WorkoutSet> findBySessionIdOrderByLoggedAtAsc(UUID sessionId);

    List<WorkoutSet> findBySessionIdIn(Collection<UUID> sessionIds);

    long countBySessionId(UUID sessionId);

    @Modifying
    @Query("delete from WorkoutSet s where s.sessionId = :sessionId")
    void deleteBySessionId(UUID sessionId);

    @Query("""
            select new com.gymtracker.workout.WorkSetRow(s.exerciseId, s.sessionId, ws.date, s.weightKg, s.reps, s.loggedAt)
            from WorkoutSet s join WorkoutSession ws on ws.id = s.sessionId
            where ws.userId = :userId and s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null""")
    List<WorkSetRow> findEndedWorkSets(UUID userId);

    @Query("""
            select new com.gymtracker.workout.WorkSetRow(s.exerciseId, s.sessionId, ws.date, s.weightKg, s.reps, s.loggedAt)
            from WorkoutSet s join WorkoutSession ws on ws.id = s.sessionId
            where ws.userId = :userId and s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null
              and s.exerciseId = :exerciseId""")
    List<WorkSetRow> findEndedWorkSetsForExercise(UUID userId, UUID exerciseId);

    @Query("""
            select new com.gymtracker.workout.WeeklySetRow(ws.date, e.muscleGroup)
            from WorkoutSet s
              join WorkoutSession ws on ws.id = s.sessionId
              join com.gymtracker.exercise.Exercise e on e.id = s.exerciseId
            where ws.userId = :userId and s.type = com.gymtracker.workout.SetType.WORK and ws.endedAt is not null
              and ws.date between :from and :to""")
    List<WeeklySetRow> findEndedWorkSetMuscles(UUID userId, LocalDate from, LocalDate to);
}
