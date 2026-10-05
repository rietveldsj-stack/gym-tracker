package com.gymtracker.workout;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SessionResponse(UUID id, LocalDate date, Instant startedAt, Instant endedAt, List<SetResponse> sets) {
}
