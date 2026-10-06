package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Upgrading a database that still holds single-user data (schema V1) starts every account empty. */
class MigrationUpgradeTest extends IntegrationTestBase {

    private static final String SCHEMA = "upgrade_check";

    @Autowired
    DataSource dataSource;

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load();
    }

    @Test
    void upgradeEmptiesTheOwnedTables() {
        flyway("1").migrate();
        jdbc.update("insert into " + SCHEMA + ".exercise (id, name, muscle_group, archived, created_at) "
                + "values (gen_random_uuid(), 'Squat', 'QUADS', false, now())");
        jdbc.update("insert into " + SCHEMA + ".workout_session (id, session_date, started_at) "
                + "values (gen_random_uuid(), current_date, now())");
        jdbc.update("insert into " + SCHEMA + ".push_subscription (endpoint, p256dh, auth, created_at) "
                + "values ('https://push.example/1', 'k', 'a', now())");
        jdbc.update("insert into " + SCHEMA + ".persistent_logins (username, series, token, last_used) "
                + "values ('old-user', 'series', 'token', now())");

        flyway("latest").migrate();

        for (String table : new String[] {"exercise", "workout_session", "workout_set", "push_subscription", "persistent_logins"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + "." + table, Integer.class))
                    .as(table).isZero();
        }
    }
}
