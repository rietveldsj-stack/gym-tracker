package com.gymtracker.exercise;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ExerciseRequest(@NotBlank @Size(max = 60) String name, @NotNull MuscleGroup muscleGroup) {
}
