package com.gymtracker.stats;

import com.gymtracker.common.BadRequestException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController {

    private final StatsService stats;
    private final Clock clock;

    StatsController(StatsService stats, Clock clock) {
        this.stats = stats;
        this.clock = clock;
    }

    @GetMapping("/api/exercises/{id}/stats")
    ExerciseStats exerciseStats(@PathVariable UUID id) {
        return stats.exerciseStats(id);
    }

    @GetMapping("/api/stats/weekly")
    List<WeekStats> weekly(@RequestParam(defaultValue = "12") int weeks,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate today) {
        if (weeks < 1 || weeks > 52) {
            throw new BadRequestException("weeks must be between 1 and 52");
        }
        return stats.weekly(weeks, today != null ? today : LocalDate.now(clock));
    }
}
