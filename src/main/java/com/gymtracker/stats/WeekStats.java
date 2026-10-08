package com.gymtracker.stats;

import com.gymtracker.exercise.MuscleGroup;
import java.time.LocalDate;
import java.util.Map;

public record WeekStats(LocalDate weekStart, Map<MuscleGroup, Integer> workSetsByMuscle) {
}
