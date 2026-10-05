package com.gymtracker.workout;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record EndSessionRequest(@NotNull Instant endedAt) {
}
