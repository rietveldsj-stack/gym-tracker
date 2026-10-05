package com.gymtracker.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PushApiTest extends IntegrationTestBase {

    @Autowired
    FakePushSender pushSender;

    @Autowired
    RestTimerService restTimer;

    @AfterEach
    void reset() {
        restTimer.cancel();
        pushSender.reset();
    }

    private void subscribe(String endpoint, String p256dh, String auth) throws Exception {
        apiPut("/api/push/subscription", """
                {"endpoint": "%s", "keys": {"p256dh": "%s", "auth": "%s"}}""".formatted(endpoint, p256dh, auth))
                .andExpect(status().isOk());
    }

    private void scheduleIn(Duration delay) throws Exception {
        apiPut("/api/rest-timer", """
                {"endsAt": "%s"}""".formatted(Instant.now().plus(delay))).andExpect(status().isNoContent());
    }

    private static void waitFor(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void exposesPublicKey() throws Exception {
        apiGet("/api/push/public-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value(org.hamcrest.Matchers.startsWith("BLeb9PGJ")));
    }

    @Test
    void savesSubscriptionOnceAndUpdatesItsKeys() throws Exception {
        subscribe("https://push.example/1", "key-1", "auth-1");
        subscribe("https://push.example/1", "key-2", "auth-2");
        assertThat(jdbc.queryForObject("select count(*) from push_subscription", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select p256dh from push_subscription", String.class)).isEqualTo("key-2");
    }

    @Test
    void validatesSubscription() throws Exception {
        apiPut("/api/push/subscription", """
                {"endpoint": "https://push.example/1"}""").andExpect(status().isBadRequest());
    }

    @Test
    void sendsAlertAtEndTime() throws Exception {
        subscribe("https://push.example/1", "key", "auth");
        scheduleIn(Duration.ofSeconds(1));
        assertThat(pushSender.sent()).isEmpty();
        waitFor(() -> pushSender.sent().size() == 1, Duration.ofSeconds(5));
        assertThat(pushSender.payloads().getFirst()).contains("Rest's over");
    }

    @Test
    void newTimerReplacesTheOldOne() throws Exception {
        subscribe("https://push.example/1", "key", "auth");
        scheduleIn(Duration.ofSeconds(1));
        scheduleIn(Duration.ofSeconds(3));
        Thread.sleep(2000);
        assertThat(pushSender.sent()).isEmpty();
        waitFor(() -> pushSender.sent().size() == 1, Duration.ofSeconds(4));
        Thread.sleep(500);
        assertThat(pushSender.sent()).hasSize(1);
    }

    @Test
    void cancelStopsTheAlert() throws Exception {
        subscribe("https://push.example/1", "key", "auth");
        scheduleIn(Duration.ofSeconds(1));
        apiDelete("/api/rest-timer").andExpect(status().isNoContent());
        Thread.sleep(2000);
        assertThat(pushSender.sent()).isEmpty();
    }

    @Test
    void cancelWithoutTimerIsFine() throws Exception {
        apiDelete("/api/rest-timer").andExpect(status().isNoContent());
    }

    @Test
    void rejectsPastOrFarFutureEndTimes() throws Exception {
        apiPut("/api/rest-timer", """
                {"endsAt": "%s"}""".formatted(Instant.now().minusSeconds(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("endsAt must be in the future and at most 1 hour away"));
        apiPut("/api/rest-timer", """
                {"endsAt": "%s"}""".formatted(Instant.now().plus(Duration.ofMinutes(61))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void goneSubscriptionIsRemoved() throws Exception {
        subscribe("https://push.example/old", "key", "auth");
        subscribe("https://push.example/new", "key", "auth");
        pushSender.markGone("https://push.example/old");
        scheduleIn(Duration.ofSeconds(1));
        waitFor(() -> pushSender.sent().size() == 2, Duration.ofSeconds(5));
        waitFor(() -> jdbc.queryForObject("select count(*) from push_subscription", Integer.class) == 1,
                Duration.ofSeconds(2));
        assertThat(jdbc.queryForObject("select endpoint from push_subscription", String.class))
                .isEqualTo("https://push.example/new");
    }
}
