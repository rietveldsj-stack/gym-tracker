package com.gymtracker;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** The account most tests act as, and a quick way to add more. */
public final class TestUsers {

    public static final String EMAIL = "tester@example.com";
    public static final String PASSWORD = "secret-pass";
    private static final String HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    private TestUsers() {
    }

    /** Adds an account with {@link #PASSWORD} as its password. */
    public static UUID insert(JdbcTemplate jdbc, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user (id, email, password_hash, created_at, password_changed_at) "
                + "values (?, ?, ?, now(), now())", id, email, HASH);
        return id;
    }
}
