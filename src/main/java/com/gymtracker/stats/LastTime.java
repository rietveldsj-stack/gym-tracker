package com.gymtracker.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LastTime(BigDecimal weightKg, int reps, LocalDate date) {
}
