package com.gymtracker.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SessionPoint(LocalDate date, BigDecimal maxWeightKg, BigDecimal est1rmKg) {
}
