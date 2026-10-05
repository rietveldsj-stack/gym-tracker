package com.gymtracker.stats;

import static java.util.stream.Collectors.groupingBy;

import com.gymtracker.workout.WorkSetRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure calculations over WORK sets from ended sessions of one exercise (spec §5). */
public final class RecordsCalculator {

    private static final BigDecimal THIRTY = BigDecimal.valueOf(30);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private RecordsCalculator() {
    }

    public static Records records(List<WorkSetRow> rows) {
        RecordSet heaviest = rows.stream()
                .filter(row -> row.weightKg().signum() > 0)
                .max(Comparator.comparing(WorkSetRow::weightKg)
                        .thenComparingInt(WorkSetRow::reps)
                        .thenComparing(WorkSetRow::loggedAt, Comparator.reverseOrder()))
                .map(row -> new RecordSet(row.weightKg(), row.reps(), row.date()))
                .orElse(null);

        // TreeMap compares BigDecimals by value, so 40.0 and 40.00 share a key.
        Map<BigDecimal, WorkSetRow> bestByWeight = new TreeMap<>(Comparator.reverseOrder());
        rows.stream().sorted(Comparator.comparing(WorkSetRow::loggedAt)).forEach(row -> {
            WorkSetRow best = bestByWeight.get(row.weightKg());
            if (best == null || row.reps() > best.reps()) {
                bestByWeight.put(row.weightKg(), row);
            }
        });
        List<RecordSet> repRecords = bestByWeight.values().stream()
                .map(row -> new RecordSet(row.weightKg(), row.reps(), row.date()))
                .toList();
        return new Records(heaviest, repRecords);
    }

    public static LastTime lastTime(List<WorkSetRow> rows) {
        return rows.stream()
                .max(Comparator.comparing(WorkSetRow::loggedAt))
                .map(row -> new LastTime(row.weightKg(), row.reps(), row.date()))
                .orElse(null);
    }

    public static List<SessionPoint> sessionPoints(List<WorkSetRow> rows) {
        Map<?, List<WorkSetRow>> bySession = rows.stream()
                .filter(row -> row.weightKg().signum() > 0)
                .collect(groupingBy(WorkSetRow::sessionId));
        record Timed(Instant firstLoggedAt, SessionPoint point) {
        }
        List<Timed> points = new ArrayList<>();
        for (List<WorkSetRow> sessionRows : bySession.values()) {
            WorkSetRow first = sessionRows.stream().min(Comparator.comparing(WorkSetRow::loggedAt)).orElseThrow();
            BigDecimal max = sessionRows.stream().map(WorkSetRow::weightKg).max(Comparator.naturalOrder()).orElseThrow();
            BigDecimal est = sessionRows.stream()
                    .map(row -> estimatedOneRepMax(row.weightKg(), row.reps()))
                    .max(Comparator.naturalOrder())
                    .orElseThrow();
            points.add(new Timed(first.loggedAt(), new SessionPoint(first.date(), max, est)));
        }
        return points.stream().sorted(Comparator.comparing(Timed::firstLoggedAt)).map(Timed::point).toList();
    }

    /** Epley: weight × (1 + reps/30); a 1-rep set counts as its own weight. Rounded to 0.5 kg. */
    public static BigDecimal estimatedOneRepMax(BigDecimal weight, int reps) {
        BigDecimal raw = reps == 1
                ? weight
                : weight.multiply(BigDecimal.valueOf(30L + reps)).divide(THIRTY, 6, RoundingMode.HALF_UP);
        return raw.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO, 1, RoundingMode.UNNECESSARY);
    }
}
