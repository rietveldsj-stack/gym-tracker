package com.gymtracker.workout;

import com.gymtracker.exercise.Exercise;
import com.gymtracker.exercise.MuscleGroup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SetResponse(UUID id, UUID sessionId, UUID exerciseId, String exerciseName, MuscleGroup muscleGroup,
                          BigDecimal weightKg, int reps, SetType type, Instant loggedAt) {

    static SetResponse of(WorkoutSet set, Exercise exercise) {
        return new SetResponse(set.getId(), set.getSessionId(), set.getExerciseId(), exercise.getName(),
                exercise.getMuscleGroup(), set.getWeightKg(), set.getReps(), set.getType(), set.getLoggedAt());
    }
}
