package com.gymtracker.exercise;

import java.util.UUID;

public record ExerciseResponse(UUID id, String name, MuscleGroup muscleGroup) {

    static ExerciseResponse of(Exercise exercise) {
        return new ExerciseResponse(exercise.getId(), exercise.getName(), exercise.getMuscleGroup());
    }
}
