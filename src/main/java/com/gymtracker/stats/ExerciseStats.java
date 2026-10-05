package com.gymtracker.stats;

import java.util.List;

public record ExerciseStats(Records records, List<SessionPoint> sessions) {
}
