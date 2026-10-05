package com.gymtracker.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VapidKeyGeneratorTest {

    @Test
    void generatesKeysThatWebPushAccepts() throws Exception {
        String[] keys = VapidKeyGenerator.generate();
        assertThat(keys[0]).hasSize(87).matches("[A-Za-z0-9_-]+");
        assertThat(keys[1]).hasSize(43).matches("[A-Za-z0-9_-]+");
        new WebPushSender(keys[0], keys[1], "mailto:test@example.com");
    }

    @Test
    void refusesToStartWithoutKeys() {
        assertThatThrownBy(() -> new WebPushSender("", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VAPID_PUBLIC_KEY");
    }
}
