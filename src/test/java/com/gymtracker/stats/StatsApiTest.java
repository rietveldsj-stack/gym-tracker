package com.gymtracker.stats;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatsApiTest extends IntegrationTestBase {

    @Test
    void exercisesIncludeLastTimeAndRecordsFromEndedSessionsOnly() throws Exception {
        UUID squat = createExercise("Squat", "QUADS");
        UUID ended = startSession("2026-09-28T08:00:00Z");
        logSet(ended, squat, "20", 10, "WARMUP", "2026-09-28T08:05:00Z");
        logSet(ended, squat, "60", 5, "WORK", "2026-09-28T08:10:00Z");
        logSet(ended, squat, "50", 8, "WORK", "2026-09-28T08:20:00Z");
        endSession(ended, "2026-09-28T09:00:00Z");
        UUID open = startSession("2026-10-05T08:00:00Z");
        logSet(open, squat, "100", 1, "WORK", "2026-10-05T08:10:00Z");

        apiGet("/api/exercises")
                .andExpect(jsonPath("$[0].lastTime.weightKg").value(50.0))
                .andExpect(jsonPath("$[0].lastTime.reps").value(8))
                .andExpect(jsonPath("$[0].lastTime.date").value("2026-09-28"))
                .andExpect(jsonPath("$[0].records.heaviest.weightKg").value(60.0))
                .andExpect(jsonPath("$[0].records.heaviest.reps").value(5))
                .andExpect(jsonPath("$[0].records.repRecords", hasSize(2)))
                .andExpect(jsonPath("$[0].records.repRecords[0].weightKg").value(60.0));
    }

    @Test
    void exerciseWithoutHistoryHasEmptyRecords() throws Exception {
        createExercise("Squat", "QUADS");
        apiGet("/api/exercises")
                .andExpect(jsonPath("$[0].lastTime").value(nullValue()))
                .andExpect(jsonPath("$[0].records.heaviest").value(nullValue()))
                .andExpect(jsonPath("$[0].records.repRecords", hasSize(0)));
    }

    @Test
    void exerciseStatsHaveOnePointPerEndedSession() throws Exception {
        UUID squat = createExercise("Squat", "QUADS");
        UUID session = startSession("2026-09-28T08:00:00Z");
        logSet(session, squat, "60", 5, "WORK", "2026-09-28T08:10:00Z");
        logSet(session, squat, "50", 8, "WORK", "2026-09-28T08:20:00Z");
        endSession(session, "2026-09-28T09:00:00Z");

        apiGet("/api/exercises/" + squat + "/stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.heaviest.weightKg").value(60.0))
                .andExpect(jsonPath("$.sessions", hasSize(1)))
                .andExpect(jsonPath("$.sessions[0].date").value("2026-09-28"))
                .andExpect(jsonPath("$.sessions[0].maxWeightKg").value(60.0))
                .andExpect(jsonPath("$.sessions[0].est1rmKg").value(70.0));
        apiGet("/api/exercises/" + UUID.randomUUID() + "/stats")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Exercise not found"));
    }

    @Test
    void weeklyCountsEndedSessionsPerMondayWeek() throws Exception {
        UUID squat = createExercise("Squat", "QUADS");
        UUID bench = createExercise("Bench press", "CHEST");
        UUID sunday = startSession("2026-10-04T08:00:00Z");
        logSet(sunday, squat, "20", 10, "WARMUP", "2026-10-04T08:05:00Z");
        endSession(sunday, "2026-10-04T08:30:00Z");
        UUID monday = startSession("2026-10-05T08:00:00Z");
        logSet(monday, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        logSet(monday, squat, "60", 5, "WORK", "2026-10-05T08:15:00Z");
        logSet(monday, bench, "40", 8, "WORK", "2026-10-05T08:25:00Z");
        logSet(monday, bench, "20", 10, "WARMUP", "2026-10-05T08:20:00Z");
        endSession(monday, "2026-10-05T09:00:00Z");
        UUID open = startSession("2026-10-06T08:00:00Z");
        logSet(open, squat, "60", 5, "WORK", "2026-10-06T08:10:00Z");

        apiGet("/api/stats/weekly?weeks=3&today=2026-10-07")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].weekStart").value("2026-09-21"))
                .andExpect(jsonPath("$[0].workouts").value(0))
                .andExpect(jsonPath("$[1].weekStart").value("2026-09-28"))
                .andExpect(jsonPath("$[1].workouts").value(1))
                .andExpect(jsonPath("$[1].workSetsByMuscle").isEmpty())
                .andExpect(jsonPath("$[2].weekStart").value("2026-10-05"))
                .andExpect(jsonPath("$[2].workouts").value(1))
                .andExpect(jsonPath("$[2].workSetsByMuscle.QUADS").value(2))
                .andExpect(jsonPath("$[2].workSetsByMuscle.CHEST").value(1));
    }

    @Test
    void weeklyValidatesWeeksAndDefaultsToTwelve() throws Exception {
        apiGet("/api/stats/weekly?weeks=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("weeks must be between 1 and 52"));
        apiGet("/api/stats/weekly?weeks=53").andExpect(status().isBadRequest());
        apiGet("/api/stats/weekly").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(12)));
    }
}
