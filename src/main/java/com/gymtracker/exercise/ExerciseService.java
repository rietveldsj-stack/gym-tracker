package com.gymtracker.exercise;

import com.gymtracker.common.ConflictException;
import com.gymtracker.common.NotFoundException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ExerciseService {

    private final ExerciseRepository exercises;
    private final Clock clock;

    public ExerciseService(ExerciseRepository exercises, Clock clock) {
        this.exercises = exercises;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Exercise> listActive() {
        return exercises.findByArchivedFalse().stream()
                .sorted(Comparator.comparing(Exercise::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Exercise upsert(UUID id, ExerciseRequest request) {
        String name = request.name().strip();
        if (exercises.existsActiveNameExcluding(name, id)) {
            throw new ConflictException("You already have an exercise called '" + name + "'");
        }
        return exercises.findById(id)
                .map(existing -> {
                    if (existing.isArchived()) {
                        throw new ConflictException("This exercise has been deleted");
                    }
                    existing.update(name, request.muscleGroup());
                    return existing;
                })
                .orElseGet(() -> exercises.save(new Exercise(id, name, request.muscleGroup(), clock.instant())));
    }

    public void archive(UUID id) {
        exercises.findById(id).orElseThrow(() -> new NotFoundException("Exercise not found")).archive();
    }
}
