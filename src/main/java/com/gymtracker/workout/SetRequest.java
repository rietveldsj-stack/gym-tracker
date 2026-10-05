package com.gymtracker.workout;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SetRequest(
        @NotNull UUID sessionId,
        @NotNull UUID exerciseId,
        @NotNull @DecimalMin("0") @DecimalMax("500") BigDecimal weightKg,
        @NotNull @Min(1) @Max(100) Integer reps,
        @NotNull SetType type,
        @NotNull Instant loggedAt) {
}
