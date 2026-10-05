package com.gymtracker.workout;

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
}
