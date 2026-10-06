package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {

    @Test
    void refusesToStartWithoutRememberMeKey() {
        assertThatThrownBy(() -> SecurityConfig.requireRememberMeKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REMEMBER_ME_KEY");
    }
}
