package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class PasswordResetServiceTest {

    @Test
    void refusesToStartWithoutBaseUrl() {
        assertThatThrownBy(() -> new PasswordResetService(null, null, null, null, null, Clock.systemUTC(), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_BASE_URL");
    }
}
