package com.gymtracker.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExerciseApiTest extends IntegrationTestBase {

    private static String body(String name, String group) {
        return """
                {"name": "%s", "muscleGroup": "%s"}""".formatted(name, group);
    }

    @Test
    void createsAndListsExercisesSortedByNameIgnoringCase() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("squat", "QUADS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("squat"))
                .andExpect(jsonPath("$.muscleGroup").value("QUADS"));
        apiPut("/api/exercises/" + UUID.randomUUID(), body("Deadlift", "BACK")).andExpect(status().isOk());
        apiPut("/api/exercises/" + UUID.randomUUID(), body("bench press", "CHEST")).andExpect(status().isOk());

        apiGet("/api/exercises")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains("bench press", "Deadlift", "squat")));
    }

    @Test
    void putIsIdempotentAndUpdates() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + id, body("Back squat", "GLUTES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Back squat"))
                .andExpect(jsonPath("$.muscleGroup").value("GLUTES"));
        assertThat(jdbc.queryForObject("select count(*) from exercise", Integer.class)).isEqualTo(1);
    }

    @Test
    void trimsName() throws Exception {
        apiPut("/api/exercises/" + UUID.randomUUID(), body("  Squat  ", "QUADS"))
                .andExpect(jsonPath("$.name").value("Squat"));
    }

    @Test
    void rejectsDuplicateNameIgnoringCase() throws Exception {
        apiPut("/api/exercises/" + UUID.randomUUID(), body("Squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + UUID.randomUUID(), body("squat", "GLUTES"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You already have an exercise called 'squat'"));
    }

    @Test
    void renamingToOwnNameWithDifferentCaseIsAllowed() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("squat", "QUADS")).andExpect(status().isOk());
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
    }

    @Test
    void archivingHidesExerciseAndFreesItsName() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("Squat", "QUADS")).andExpect(status().isOk());
        apiDelete("/api/exercises/" + id).andExpect(status().isNoContent());
        apiDelete("/api/exercises/" + id).andExpect(status().isNoContent());
        apiGet("/api/exercises").andExpect(jsonPath("$", hasSize(0)));
        apiPut("/api/exercises/" + UUID.randomUUID(), body("Squat", "QUADS")).andExpect(status().isOk());
    }

    @Test
    void editingArchivedExerciseIsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, body("Squat", "QUADS"));
        apiDelete("/api/exercises/" + id);
        apiPut("/api/exercises/" + id, body("Squat 2", "QUADS"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This exercise has been deleted"));
    }

    @Test
    void archivingUnknownExerciseIs404() throws Exception {
        apiDelete("/api/exercises/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exercise not found"));
    }

    @Test
    void validatesInput() throws Exception {
        String url = "/api/exercises/" + UUID.randomUUID();
        apiPut(url, body("   ", "QUADS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("name")));
        apiPut(url, body("x".repeat(61), "QUADS")).andExpect(status().isBadRequest());
        apiPut(url, """
                {"name": "Squat"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("muscleGroup")));
        apiPut(url, body("Squat", "LEGS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));
        apiPut("/api/exercises/not-a-uuid", body("Squat", "QUADS")).andExpect(status().isBadRequest());
    }

    @Test
    void requiresSignIn() throws Exception {
        mvc.perform(get("/api/exercises")).andExpect(status().isUnauthorized());
    }
}
