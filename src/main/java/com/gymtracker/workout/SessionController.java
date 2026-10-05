package com.gymtracker.workout;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
class SessionController {

    private final WorkoutService service;

    SessionController(WorkoutService service) {
        this.service = service;
    }

    @GetMapping("/active")
    ResponseEntity<SessionResponse> active() {
        return service.active().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    List<SessionSummary> history() {
        return service.history();
    }

    @GetMapping("/{id}")
    SessionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    SessionResponse start(@PathVariable UUID id, @Valid @RequestBody StartSessionRequest request) {
        return service.start(id, request);
    }

    @PostMapping("/{id}/end")
    EndSessionResponse end(@PathVariable UUID id, @Valid @RequestBody EndSessionRequest request) {
        return service.end(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void discard(@PathVariable UUID id) {
        service.discard(id);
    }
}
