package com.gymtracker.workout;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;

public record StartSessionRequest(@NotNull LocalDate date, @NotNull Instant startedAt) {
}
