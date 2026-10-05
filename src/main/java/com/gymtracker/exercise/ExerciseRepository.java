package com.gymtracker.exercise;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExerciseRepository extends JpaRepository<Exercise, UUID> {

    List<Exercise> findByArchivedFalse();

    @Query("""
            select count(e) > 0 from Exercise e
            where lower(e.name) = lower(:name) and e.archived = false and e.id <> :id""")
    boolean existsActiveNameExcluding(String name, UUID id);
}
