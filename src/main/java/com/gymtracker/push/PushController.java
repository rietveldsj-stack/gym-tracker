package com.gymtracker.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class PushController {

    record Keys(@NotBlank String p256dh, @NotBlank String auth) {
    }

    record SubscriptionRequest(@NotBlank @Size(max = 2048) String endpoint, @NotNull @Valid Keys keys) {
    }

    record RestTimerRequest(@NotNull Instant endsAt) {
    }

    private final String publicKey;
    private final PushSubscriptionService subscriptions;
    private final RestTimerService restTimer;

    PushController(@Value("${app.vapid.public-key}") String publicKey, PushSubscriptionService subscriptions,
                   RestTimerService restTimer) {
        this.publicKey = publicKey;
        this.subscriptions = subscriptions;
        this.restTimer = restTimer;
    }

    @GetMapping("/push/public-key")
    Map<String, String> publicKey() {
        return Map.of("publicKey", publicKey);
    }

    @PutMapping("/push/subscription")
    void subscribe(@Valid @RequestBody SubscriptionRequest request) {
        subscriptions.save(request.endpoint(), request.keys().p256dh(), request.keys().auth());
    }

    @PutMapping("/rest-timer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void schedule(@Valid @RequestBody RestTimerRequest request) {
        restTimer.schedule(request.endsAt());
    }

    @DeleteMapping("/rest-timer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel() {
        restTimer.cancel();
    }
}
