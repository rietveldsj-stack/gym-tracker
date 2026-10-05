package com.gymtracker.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.gymtracker.workout.WorkSetRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordsCalculatorTest {

    private static final UUID EXERCISE = UUID.randomUUID();
    private static final UUID S1 = UUID.randomUUID();
    private static final UUID S2 = UUID.randomUUID();
    private static final UUID S3 = UUID.randomUUID();

    private static WorkSetRow row(UUID session, String date, String weight, int reps, String time) {
        return new WorkSetRow(EXERCISE, session, LocalDate.parse(date), new BigDecimal(weight), reps,
                Instant.parse(date + "T" + time + ":00Z"));
    }

    @Test
    void emptyHistoryHasNoRecords() {
        Records records = RecordsCalculator.records(List.of());
        assertThat(records.heaviest()).isNull();
        assertThat(records.repRecords()).isEmpty();
        assertThat(RecordsCalculator.lastTime(List.of())).isNull();
        assertThat(RecordsCalculator.sessionPoints(List.of())).isEmpty();
    }

    @Test
    void heaviestIgnoresBodyweightAndBreaksTiesByRepsThenEarliestDate() {
        Records records = RecordsCalculator.records(List.of(
                row(S1, "2026-09-01", "60.00", 5, "08:00"),
                row(S1, "2026-09-01", "0.00", 20, "08:10"),
                row(S2, "2026-09-08", "60.00", 6, "08:00"),
                row(S3, "2026-09-15", "60.00", 6, "08:00")));
        assertThat(records.heaviest().weightKg()).isEqualByComparingTo("60");
        assertThat(records.heaviest().reps()).isEqualTo(6);
        assertThat(records.heaviest().date()).isEqualTo(LocalDate.parse("2026-09-08"));
    }

    @Test
    void onlyBodyweightGivesRepRecordButNoHeaviest() {
        Records records = RecordsCalculator.records(List.of(row(S1, "2026-09-01", "0.00", 20, "08:00")));
        assertThat(records.heaviest()).isNull();
        assertThat(records.repRecords()).hasSize(1);
        assertThat(records.repRecords().getFirst().reps()).isEqualTo(20);
    }

    @Test
    void repRecordsHoldMostRepsPerWeightHeaviestFirstWithEarliestDate() {
        Records records = RecordsCalculator.records(List.of(
                row(S1, "2026-09-01", "40.00", 10, "08:00"),
                row(S1, "2026-09-01", "0.00", 15, "08:05"),
                row(S2, "2026-09-08", "40.00", 12, "08:00"),
                row(S2, "2026-09-08", "50.00", 5, "08:10"),
                row(S3, "2026-09-15", "40.00", 12, "08:00")));
        List<RecordSet> reps = records.repRecords();
        assertThat(reps).extracting(r -> r.weightKg().intValue()).containsExactly(50, 40, 0);
        assertThat(reps.get(1).reps()).isEqualTo(12);
        assertThat(reps.get(1).date()).isEqualTo(LocalDate.parse("2026-09-08"));
        assertThat(reps.get(2).reps()).isEqualTo(15);
    }

    @Test
    void lastTimeIsMostRecentSet() {
        LastTime last = RecordsCalculator.lastTime(List.of(
                row(S2, "2026-09-08", "45.00", 8, "08:00"),
                row(S1, "2026-09-01", "40.00", 10, "08:00"),
                row(S2, "2026-09-08", "47.50", 6, "08:20")));
        assertThat(last.weightKg()).isEqualByComparingTo("47.5");
        assertThat(last.reps()).isEqualTo(6);
        assertThat(last.date()).isEqualTo(LocalDate.parse("2026-09-08"));
    }

    @Test
    void estimatedOneRepMaxUsesEpleyRoundedToHalfKilo() {
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("40"), 10)).isEqualByComparingTo("53.5");
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("100"), 1)).isEqualByComparingTo("100.0");
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("60"), 5)).isEqualByComparingTo("70.0");
        assertThat(RecordsCalculator.estimatedOneRepMax(new BigDecimal("42.5"), 8)).isEqualByComparingTo("54.0");
    }

    @Test
    void sessionPointsAreOnePerSessionInOrderAndSkipBodyweightOnlySessions() {
        List<SessionPoint> points = RecordsCalculator.sessionPoints(List.of(
                row(S2, "2026-09-08", "60.00", 5, "08:00"),
                row(S2, "2026-09-08", "50.00", 10, "08:10"),
                row(S1, "2026-09-01", "55.00", 5, "08:00"),
                row(S3, "2026-09-15", "0.00", 20, "08:00")));
        assertThat(points).hasSize(2);
        assertThat(points.get(0).date()).isEqualTo(LocalDate.parse("2026-09-01"));
        assertThat(points.get(0).maxWeightKg()).isEqualByComparingTo("55");
        assertThat(points.get(0).est1rmKg()).isEqualByComparingTo("64.0");
        assertThat(points.get(1).maxWeightKg()).isEqualByComparingTo("60");
        assertThat(points.get(1).est1rmKg()).isEqualByComparingTo("70.0");
    }
}
