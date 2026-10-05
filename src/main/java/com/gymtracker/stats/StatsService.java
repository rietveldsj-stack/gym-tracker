package com.gymtracker.stats;

import static java.util.stream.Collectors.groupingBy;

import com.gymtracker.common.NotFoundException;
import com.gymtracker.exercise.ExerciseRepository;
import com.gymtracker.exercise.MuscleGroup;
import com.gymtracker.workout.WeeklySetRow;
import com.gymtracker.workout.WorkSetRow;
import com.gymtracker.workout.WorkoutSession;
import com.gymtracker.workout.WorkoutSessionRepository;
import com.gymtracker.workout.WorkoutSetRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StatsService {

    private final WorkoutSetRepository sets;
    private final WorkoutSessionRepository sessions;
    private final ExerciseRepository exercises;

    public StatsService(WorkoutSetRepository sets, WorkoutSessionRepository sessions, ExerciseRepository exercises) {
        this.sets = sets;
        this.sessions = sessions;
        this.exercises = exercises;
    }

    public Map<UUID, ExerciseHistory> historyByExercise() {
        Map<UUID, ExerciseHistory> result = new HashMap<>();
        sets.findEndedWorkSets().stream()
                .collect(groupingBy(WorkSetRow::exerciseId))
                .forEach((exerciseId, rows) -> result.put(exerciseId, ExerciseHistory.of(rows)));
        return result;
    }

    public ExerciseHistory historyFor(UUID exerciseId) {
        return ExerciseHistory.of(sets.findEndedWorkSetsForExercise(exerciseId));
    }

    public ExerciseStats exerciseStats(UUID exerciseId) {
        if (!exercises.existsById(exerciseId)) {
            throw new NotFoundException("Exercise not found");
        }
        List<WorkSetRow> rows = sets.findEndedWorkSetsForExercise(exerciseId);
        return new ExerciseStats(RecordsCalculator.records(rows), RecordsCalculator.sessionPoints(rows));
    }

    /** The {@code weeks} Monday-to-Sunday weeks ending with the week that contains {@code today}, oldest first. */
    public List<WeekStats> weekly(int weeks, LocalDate today) {
        LocalDate thisWeek = weekStart(today);
        LocalDate from = thisWeek.minusWeeks(weeks - 1L);
        LocalDate to = thisWeek.plusDays(6);

        Map<LocalDate, Integer> workouts = new HashMap<>();
        for (WorkoutSession session : sessions.findByEndedAtIsNotNullAndDateBetween(from, to)) {
            workouts.merge(weekStart(session.getDate()), 1, Integer::sum);
        }
        Map<LocalDate, Map<MuscleGroup, Integer>> muscles = new HashMap<>();
        for (WeeklySetRow row : sets.findEndedWorkSetMuscles(from, to)) {
            muscles.computeIfAbsent(weekStart(row.date()), week -> new EnumMap<>(MuscleGroup.class))
                    .merge(row.muscleGroup(), 1, Integer::sum);
        }

        List<WeekStats> result = new ArrayList<>();
        for (int i = 0; i < weeks; i++) {
            LocalDate week = from.plusWeeks(i);
            result.add(new WeekStats(week, workouts.getOrDefault(week, 0),
                    muscles.getOrDefault(week, new EnumMap<>(MuscleGroup.class))));
        }
        return result;
    }

    private static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
