package com.gymtracker.workout;

import com.gymtracker.exercise.MuscleGroup;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SessionSummary(UUID id, LocalDate date, Instant startedAt, Instant endedAt, long durationSeconds,
                             int setCount, List<MuscleGroup> muscleGroups) {
}
