package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityConfigTest {

    @Test
    void refusesToStartWithoutCredentials() {
        var encoder = new BCryptPasswordEncoder();
        assertThatThrownBy(() -> SecurityConfig.singleUser("", "pw", encoder))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_USERNAME");
        assertThatThrownBy(() -> SecurityConfig.singleUser("her", " ", encoder))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithoutRememberMeKey() {
        assertThatThrownBy(() -> SecurityConfig.requireRememberMeKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REMEMBER_ME_KEY");
    }
}
