package com.gymtracker.exercise;

import jakarta.validation.Valid;
import java.util.List;
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

    ExerciseController(ExerciseService service) {
        this.service = service;
    }

    @GetMapping
    List<ExerciseResponse> list() {
        return service.listActive().stream().map(ExerciseResponse::of).toList();
    }

    @PutMapping("/{id}")
    ExerciseResponse put(@PathVariable UUID id, @Valid @RequestBody ExerciseRequest request) {
        return ExerciseResponse.of(service.upsert(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.archive(id);
    }
}
