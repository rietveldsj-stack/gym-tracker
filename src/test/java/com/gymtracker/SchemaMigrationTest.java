package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SchemaMigrationTest extends IntegrationTestBase {

    @Test
    void createsAllTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("exercise", "workout_session", "workout_set", "persistent_logins", "push_subscription",
                "app_user", "password_reset_token");
    }

    @Test
    void allowsOneOpenSessionPerAccount() {
        UUID other = createUser("other@example.com");
        insertSession(testerId, null);
        insertSession(testerId, "2026-10-05T09:00:00Z");
        insertSession(other, null);
        assertThatThrownBy(() -> insertSession(testerId, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void exerciseNamesAreUniquePerAccountIgnoringCaseAmongActiveOnly() {
        UUID other = createUser("other@example.com");
        insertExercise(testerId, "Squat", true);
        insertExercise(testerId, "Squat", false);
        insertExercise(other, "Squat", false);
        assertThatThrownBy(() -> insertExercise(testerId, "squat", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSession(UUID userId, String endedAt) {
        jdbc.update("insert into workout_session (id, user_id, session_date, started_at, ended_at) "
                + "values (?, ?, date '2026-10-05', timestamptz '2026-10-05T08:00:00Z', ?::timestamptz)",
                UUID.randomUUID(), userId, endedAt);
    }

    private void insertExercise(UUID userId, String name, boolean archived) {
        jdbc.update("insert into exercise (id, user_id, name, muscle_group, archived, created_at) "
                + "values (?, ?, ?, 'QUADS', ?, now())", UUID.randomUUID(), userId, name, archived);
    }
}
