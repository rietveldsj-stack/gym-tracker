package com.gymtracker.workout;

import com.gymtracker.account.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sets")
class SetController {

    private final WorkoutService service;
    private final CurrentUser currentUser;

    SetController(WorkoutService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PutMapping("/{id}")
    SetResponse put(@PathVariable UUID id, @Valid @RequestBody SetRequest request) {
        return service.putSet(currentUser.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        service.deleteSet(currentUser.id(), id);
    }
}
