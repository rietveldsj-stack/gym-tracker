package com.gymtracker.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkoutApiTest extends IntegrationTestBase {

    private UUID squat;

    @BeforeEach
    void createSquat() throws Exception {
        squat = createExercise("Squat", "QUADS");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void startsSessionAndReturnsItAsActive() throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/sessions/" + id, """
                {"date": "2026-10-05", "startedAt": "2026-10-05T08:00:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.date").value("2026-10-05"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-05T08:00:00Z"))
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.sets", hasSize(0)));
        apiGet("/api/sessions/active").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void noActiveSessionAnswers204() throws Exception {
        apiGet("/api/sessions/active").andExpect(status().isNoContent());
    }

    @Test
    void resendingStartIsHarmless() throws Exception {
        UUID id = UUID.randomUUID();
        String body = """
                {"date": "2026-10-05", "startedAt": "2026-10-05T08:00:00Z"}""";
        apiPut("/api/sessions/" + id, body).andExpect(status().isOk());
        apiPut("/api/sessions/" + id, body).andExpect(status().isOk());
        assertThat(count("workout_session")).isEqualTo(1);
    }

    @Test
    void secondOpenSessionIsConflict() throws Exception {
        startSession("2026-10-05T08:00:00Z");
        apiPut("/api/sessions/" + UUID.randomUUID(), """
                {"date": "2026-10-05", "startedAt": "2026-10-05T09:00:00Z"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Another workout is already in progress"));
    }

    @Test
    void logsSetsAndReturnsThemInLoggedOrder() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "42.5", 8, "WORK", "2026-10-05T08:10:00Z");
        logSet(session, squat, "20", 10, "WARMUP", "2026-10-05T08:05:00Z");

        apiGet("/api/sessions/active")
                .andExpect(jsonPath("$.sets", hasSize(2)))
                .andExpect(jsonPath("$.sets[0].type").value("WARMUP"))
                .andExpect(jsonPath("$.sets[0].weightKg").value(20.0))
                .andExpect(jsonPath("$.sets[1].weightKg").value(42.5))
                .andExpect(jsonPath("$.sets[1].reps").value(8))
                .andExpect(jsonPath("$.sets[1].exerciseName").value("Squat"))
                .andExpect(jsonPath("$.sets[1].muscleGroup").value("QUADS"));
    }

    @Test
    void resendingSetIsIdempotentAndPutEditsIt() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        UUID setId = UUID.randomUUID();
        String body = setJson(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiPut("/api/sets/" + setId, body).andExpect(status().isOk());
        apiPut("/api/sets/" + setId, body).andExpect(status().isOk());
        apiPut("/api/sets/" + setId, setJson(session, squat, "40", 12, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reps").value(12));
        assertThat(count("workout_set")).isEqualTo(1);
    }

    @Test
    void validatesSets() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        String at = "2026-10-05T08:10:00Z";
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "500.25", 5, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "-1", 5, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "42.3", 5, "WORK", at))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("weightKg must be a multiple of 0.25"));
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 0, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 101, "WORK", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 5, "HEAVY", at))
                .andExpect(status().isBadRequest());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "500", 1, "WORK", at))
                .andExpect(status().isOk());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "0", 15, "WORK", at))
                .andExpect(status().isOk());
    }

    @Test
    void unknownSessionOrExerciseIs404() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(UUID.randomUUID(), squat, "40", 5, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Workout not found"));
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, UUID.randomUUID(), "40", 5, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exercise not found"));
    }

    @Test
    void setsOfEndedSessionCannotChange() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        UUID setId = logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        endSession(session, "2026-10-05T09:00:00Z");
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 10, "WORK", "2026-10-05T08:20:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This workout has already ended"));
        apiDelete("/api/sets/" + setId).andExpect(status().isConflict());
    }

    @Test
    void newSetForArchivedExerciseIsConflict() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        apiDelete("/api/exercises/" + squat).andExpect(status().isNoContent());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This exercise has been deleted"));
    }

    @Test
    void deletesSetIdempotently() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        UUID setId = logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiDelete("/api/sets/" + setId).andExpect(status().isNoContent());
        apiDelete("/api/sets/" + setId).andExpect(status().isNoContent());
        assertThat(count("workout_set")).isZero();
    }

    @Test
    void endingTwiceKeepsOriginalEndedAt() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T09:00:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discarded").value(false))
                .andExpect(jsonPath("$.session.endedAt").value("2026-10-05T09:00:00Z"));
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T10:00:00Z"}""")
                .andExpect(jsonPath("$.session.endedAt").value("2026-10-05T09:00:00Z"));
        apiGet("/api/sessions/active").andExpect(status().isNoContent());
    }

    @Test
    void endingEmptySessionDeletesIt() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T08:05:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discarded").value(true))
                .andExpect(jsonPath("$.session").value(nullValue()));
        assertThat(count("workout_session")).isZero();
        apiGet("/api/sessions").andExpect(jsonPath("$", hasSize(0)));
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T08:05:00Z"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discarded").value(true));
    }

    @Test
    void endBeforeStartIs400() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T07:00:00Z"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("endedAt must not be before startedAt"));
    }

    @Test
    void discardRemovesSessionAndItsSets() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:12:00Z");
        apiDelete("/api/sessions/" + session).andExpect(status().isNoContent());
        apiDelete("/api/sessions/" + session).andExpect(status().isNoContent());
        assertThat(count("workout_session")).isZero();
        assertThat(count("workout_set")).isZero();
    }

    @Test
    void historyListsEndedSessionsNewestFirstWithSummary() throws Exception {
        UUID bench = createExercise("Bench press", "CHEST");
        UUID first = startSession("2026-10-01T08:00:00Z");
        logSet(first, squat, "60", 5, "WORK", "2026-10-01T08:10:00Z");
        logSet(first, bench, "40", 8, "WORK", "2026-10-01T08:20:00Z");
        endSession(first, "2026-10-01T08:45:00Z");
        UUID second = startSession("2026-10-03T18:00:00Z");
        logSet(second, squat, "62.5", 5, "WORK", "2026-10-03T18:10:00Z");
        endSession(second, "2026-10-03T18:30:00Z");
        startSession("2026-10-05T08:00:00Z");

        apiGet("/api/sessions")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second.toString()))
                .andExpect(jsonPath("$[1].date").value("2026-10-01"))
                .andExpect(jsonPath("$[1].durationSeconds").value(2700))
                .andExpect(jsonPath("$[1].setCount").value(2))
                .andExpect(jsonPath("$[1].muscleGroups", contains("QUADS", "CHEST")));
    }

    @Test
    void sessionCrossingMidnightKeepsItsStartDate() throws Exception {
        UUID session = startSession("2026-10-05T23:50:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T23:55:00Z");
        endSession(session, "2026-10-06T00:30:00Z");
        apiGet("/api/sessions")
                .andExpect(jsonPath("$[0].date").value("2026-10-05"))
                .andExpect(jsonPath("$[0].durationSeconds").value(2400));
    }

    @Test
    void getsOneSessionWithItsSets() throws Exception {
        UUID session = startSession("2026-10-05T08:00:00Z");
        logSet(session, squat, "40", 10, "WORK", "2026-10-05T08:10:00Z");
        endSession(session, "2026-10-05T09:00:00Z");
        apiGet("/api/sessions/" + session)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sets[0].exerciseName").value("Squat"));
        apiGet("/api/sessions/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }
}
