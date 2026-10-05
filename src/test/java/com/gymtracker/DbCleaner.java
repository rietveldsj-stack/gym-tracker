package com.gymtracker;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DbCleaner {

    private DbCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.execute("truncate table workout_set, workout_session, exercise, persistent_logins, push_subscription");
    }
}
