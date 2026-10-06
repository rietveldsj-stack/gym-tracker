package com.gymtracker;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DbCleaner {

    private DbCleaner() {
    }

    /** Retries on deadlock: a closed browser's last requests can still be running when the next test starts. */
    public static void clean(JdbcTemplate jdbc) {
        for (int attempt = 1; ; attempt++) {
            try {
                jdbc.execute("truncate table workout_set, workout_session, exercise, persistent_logins, "
                        + "push_subscription, password_reset_token, app_user");
                return;
            } catch (PessimisticLockingFailureException e) {
                if (attempt == 5) {
                    throw e;
                }
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
