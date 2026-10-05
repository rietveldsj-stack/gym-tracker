package com.gymtracker.workout;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One WORK set from an ended session, with the session's date. */
public record WorkSetRow(UUID exerciseId, UUID sessionId, LocalDate date, BigDecimal weightKg, int reps,
                         Instant loggedAt) {
}
