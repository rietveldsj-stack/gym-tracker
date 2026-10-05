package com.gymtracker.workout;

import com.gymtracker.exercise.MuscleGroup;
import java.time.LocalDate;

/** One WORK set from an ended session: the session's date and the exercise's muscle group. */
public record WeeklySetRow(LocalDate date, MuscleGroup muscleGroup) {
}
