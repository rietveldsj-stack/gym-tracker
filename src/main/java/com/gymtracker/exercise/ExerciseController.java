package com.gymtracker.exercise;

import com.gymtracker.account.CurrentUser;
import com.gymtracker.stats.ExerciseHistory;
import com.gymtracker.stats.StatsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exercises")
class ExerciseController {

    private final ExerciseService service;
    private final StatsService stats;
    private final CurrentUser currentUser;

    ExerciseController(ExerciseService service, StatsService stats, CurrentUser currentUser) {
        this.service = service;
        this.stats = stats;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<ExerciseResponse> list() {
        UUID userId = currentUser.id();
        Map<UUID, ExerciseHistory> histories = stats.historyByExercise(userId);
        return service.listActive(userId).stream()
                .map(e -> ExerciseResponse.of(e, histories.getOrDefault(e.getId(), ExerciseHistory.EMPTY)))
                .toList();
    }

    @PutMapping("/{id}")
    ExerciseResponse put(@PathVariable UUID id, @Valid @RequestBody ExerciseRequest request) {
        UUID userId = currentUser.id();
        Exercise exercise = service.upsert(userId, id, request);
        return ExerciseResponse.of(exercise, stats.historyFor(userId, id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.archive(currentUser.id(), id);
    }
}
