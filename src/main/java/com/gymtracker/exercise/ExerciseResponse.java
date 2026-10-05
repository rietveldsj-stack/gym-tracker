package com.gymtracker.exercise;

import com.gymtracker.stats.ExerciseHistory;
import com.gymtracker.stats.LastTime;
import com.gymtracker.stats.Records;
import java.util.UUID;

public record ExerciseResponse(UUID id, String name, MuscleGroup muscleGroup, LastTime lastTime, Records records) {

    static ExerciseResponse of(Exercise exercise, ExerciseHistory history) {
        return new ExerciseResponse(exercise.getId(), exercise.getName(), exercise.getMuscleGroup(),
                history.lastTime(), history.records());
    }
}
