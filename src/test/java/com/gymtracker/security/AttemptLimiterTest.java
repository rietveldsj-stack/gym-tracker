package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    /** A clock the test moves forward by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsMaxAttemptsPerWindowAndPerKey() {
        TestClock clock = new TestClock();
        AttemptLimiter limiter = new AttemptLimiter(3, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("login|1.2.3.4")).isTrue();
        }
        assertThat(limiter.tryAcquire("login|1.2.3.4")).isFalse();
        assertThat(limiter.tryAcquire("login|5.6.7.8")).isTrue();
        assertThat(limiter.tryAcquire("forgot|1.2.3.4")).isTrue();
    }

    @Test
    void attemptsExpireAfterTheWindow() {
        TestClock clock = new TestClock();
        AttemptLimiter limiter = new AttemptLimiter(1, Duration.ofMinutes(15), clock);
        assertThat(limiter.tryAcquire("k")).isTrue();
        clock.now = clock.now.plus(Duration.ofMinutes(14));
        assertThat(limiter.tryAcquire("k")).isFalse();
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(limiter.tryAcquire("k")).isTrue();
    }
}
