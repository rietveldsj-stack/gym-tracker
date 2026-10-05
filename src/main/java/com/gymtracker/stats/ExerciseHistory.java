package com.gymtracker.stats;

import com.gymtracker.workout.WorkSetRow;
import java.util.List;

/** What the exercise list needs per exercise: "last time" and the PR records. */
public record ExerciseHistory(LastTime lastTime, Records records) {

    public static final ExerciseHistory EMPTY = new ExerciseHistory(null, Records.EMPTY);

    public static ExerciseHistory of(List<WorkSetRow> rows) {
        return new ExerciseHistory(RecordsCalculator.lastTime(rows), RecordsCalculator.records(rows));
    }
}
