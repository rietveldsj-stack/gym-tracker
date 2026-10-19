package com.gymtracker.workout;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** The read-only list of finished sessions that Cadence counts for its Workouts goal. */
class ExportApiTest extends IntegrationTestBase {

    static final String KEY = "test-export-key";

    /** A finished workout. Workouts without sets are thrown away when they end, so this logs one. */
    private void workout(String startedAt, String endedAt) throws Exception {
        var exercise = createExercise("Squat " + startedAt, "QUADS");
        var session = startSession(startedAt);
        logSet(session, exercise, "60", 5, "WORK", startedAt.replace(":00:00Z", ":10:00Z"));
        endSession(session, endedAt);
    }

    private ResultActions export(String from, String to, String key) throws Exception {
        var request = get("/api/export/sessions").param("from", from).param("to", to);
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return mvc.perform(request);
    }

    @Test
    void returnsFinishedSessionsInRange() throws Exception {
        workout("2026-10-19T16:00:00Z", "2026-10-19T17:15:00Z");
        workout("2026-10-10T16:00:00Z", "2026-10-10T17:00:00Z");
        startSession("2026-10-20T16:00:00Z"); // still open

        export("2026-10-18T22:00:00Z", "2026-10-25T23:00:00Z", KEY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].startedAt").value("2026-10-19T16:00:00Z"))
                .andExpect(jsonPath("$[0].endedAt").value("2026-10-19T17:15:00Z"));
    }

    @Test
    void wrongKeyIs401() throws Exception {
        export("2026-10-18T22:00:00Z", "2026-10-25T23:00:00Z", "guess").andExpect(status().isUnauthorized());
    }

    @Test
    void missingKeyHeaderIs401() throws Exception {
        export("2026-10-18T22:00:00Z", "2026-10-25T23:00:00Z", null).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyConfiguredAccount() throws Exception {
        createUser("other@example.com");
        actingAs = "other@example.com";
        workout("2026-10-19T16:00:00Z", "2026-10-19T17:00:00Z");
        actingAs = TestUsers.EMAIL;
        export("2026-10-18T22:00:00Z", "2026-10-25T23:00:00Z", KEY).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rangeOver62DaysIs400() throws Exception {
        export("2026-08-01T00:00:00Z", "2026-10-25T00:00:00Z", KEY).andExpect(status().isBadRequest());
        export("2026-10-25T00:00:00Z", "2026-10-01T00:00:00Z", KEY).andExpect(status().isBadRequest());
    }
}
