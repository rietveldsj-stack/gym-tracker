package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One account can never see, change or delete another account's data, even with the other account's ids. */
class IsolationApiTest extends IntegrationTestBase {

    private static final String OTHER = "other@example.com";

    private UUID squat;
    private UUID session;
    private UUID set;

    @BeforeEach
    void testerHasData() throws Exception {
        squat = createExercise("Squat", "QUADS");
        session = startSession("2026-10-05T08:00:00Z");
        set = logSet(session, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        createUser(OTHER);
    }

    private void actAsOther() {
        actingAs = OTHER;
    }

    @Test
    void listsOnlyOwnExercises() throws Exception {
        actAsOther();
        apiGet("/api/exercises").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cannotRenameOrDeleteAnotherAccountsExercise() throws Exception {
        actAsOther();
        apiPut("/api/exercises/" + squat, """
                {"name": "Mine now", "muscleGroup": "CHEST"}""").andExpect(status().isNotFound());
        apiDelete("/api/exercises/" + squat).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select name from exercise where id = ?", String.class, squat)).isEqualTo("Squat");
        assertThat(jdbc.queryForObject("select archived from exercise where id = ?", Boolean.class, squat)).isFalse();
    }

    @Test
    void twoAccountsCanHaveExercisesWithTheSameName() throws Exception {
        actAsOther();
        createExercise("Squat", "QUADS");
        assertThat(jdbc.queryForObject("select count(*) from exercise where name = 'Squat'", Integer.class)).isEqualTo(2);
    }

    @Test
    void cannotSeeOrTouchAnotherAccountsSession() throws Exception {
        actAsOther();
        apiGet("/api/sessions/active").andExpect(status().isNoContent());
        apiGet("/api/sessions/" + session).andExpect(status().isNotFound());
        apiPut("/api/sessions/" + session, """
                {"date": "2026-10-05", "startedAt": "2026-10-05T08:00:00Z"}""").andExpect(status().isNotFound());
        apiPost("/api/sessions/" + session + "/end", """
                {"endedAt": "2026-10-05T09:00:00Z"}""").andExpect(status().isOk());
        apiDelete("/api/sessions/" + session).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from workout_session where id = ? and ended_at is null",
                Integer.class, session)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workout_set where id = ?", Integer.class, set)).isEqualTo(1);
    }

    @Test
    void eachAccountHasItsOwnOpenSession() throws Exception {
        actAsOther();
        startSession("2026-10-05T08:30:00Z");
        assertThat(jdbc.queryForObject("select count(*) from workout_session where ended_at is null", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void cannotAddSetsToAnotherAccountsSessionOrWithItsExercise() throws Exception {
        actAsOther();
        UUID ownExercise = createExercise("Bench press", "CHEST");
        UUID ownSession = startSession("2026-10-05T08:30:00Z");
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, ownExercise, "40", 8, "WORK", "2026-10-05T08:40:00Z"))
                .andExpect(status().isNotFound());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(ownSession, squat, "40", 8, "WORK", "2026-10-05T08:40:00Z"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from workout_set", Integer.class)).isEqualTo(1);
    }

    @Test
    void cannotChangeOrDeleteAnotherAccountsSet() throws Exception {
        actAsOther();
        UUID ownExercise = createExercise("Bench press", "CHEST");
        UUID ownSession = startSession("2026-10-05T08:30:00Z");
        apiPut("/api/sets/" + set, setJson(ownSession, ownExercise, "1", 1, "WORK", "2026-10-05T08:40:00Z"))
                .andExpect(status().isNotFound());
        apiDelete("/api/sets/" + set).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select reps from workout_set where id = ?", Integer.class, set)).isEqualTo(5);
    }

    @Test
    void cannotEditOrDeleteAnotherAccountsEndedWorkout() throws Exception {
        endSession(session, "2026-10-05T09:00:00Z");
        actAsOther();
        UUID ownExercise = createExercise("Bench press", "CHEST");
        apiPut("/api/sets/" + set, setJson(session, ownExercise, "1", 1, "WORK", "2026-10-05T08:10:00Z"))
                .andExpect(status().isNotFound());
        apiPut("/api/sets/" + UUID.randomUUID(), setJson(session, ownExercise, "1", 1, "WORK", "2026-10-05T08:20:00Z"))
                .andExpect(status().isNotFound());
        apiDelete("/api/sets/" + set).andExpect(status().isNoContent());
        apiDelete("/api/sessions/" + session).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select reps from workout_set where id = ?", Integer.class, set)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from workout_session", Integer.class)).isEqualTo(1);
    }

    @Test
    void historyAndStatsCountOnlyOwnWorkouts() throws Exception {
        endSession(session, "2026-10-05T09:00:00Z");
        actAsOther();
        apiGet("/api/sessions").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        apiGet("/api/exercises/" + squat + "/stats").andExpect(status().isNotFound());
        apiGet("/api/stats/weekly?weeks=1&today=2026-10-05")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workouts").value(0));
    }
}
