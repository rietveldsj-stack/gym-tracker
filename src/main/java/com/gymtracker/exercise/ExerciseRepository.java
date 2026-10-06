package com.gymtracker.exercise;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExerciseRepository extends JpaRepository<Exercise, UUID> {

    List<Exercise> findByUserIdAndArchivedFalse(UUID userId);

    Optional<Exercise> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByIdAndUserId(UUID id, UUID userId);

    @Query("""
            select count(e) > 0 from Exercise e
            where e.userId = :userId and lower(e.name) = lower(:name) and e.archived = false and e.id <> :id""")
    boolean existsActiveNameExcluding(UUID userId, String name, UUID id);
}
