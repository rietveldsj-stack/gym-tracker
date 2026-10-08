package com.gymtracker.mail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Sends through Brevo's transactional email API over HTTPS (no SMTP, so hosting port rules don't matter). */
class BrevoMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoMailSender.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final URI endpoint;
    private final String apiKey;
    private final String from;

    BrevoMailSender(URI endpoint, String apiKey, String from) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.from = from;
    }

    /**
     * Sends in the background and returns at once, so asking for a reset link answers as fast whether or not the
     * account exists (only real accounts get an email). Failures are logged, never the email's text (a reset link).
     */
    @Override
    public void send(String to, String subject, String text) {
        deliver(to, subject, text).exceptionally(e -> {
            log.warn("Sending email failed: {}", e.getMessage());
            return null;
        });
    }

    /** Completes when Brevo accepted the email, or fails with a {@link MailException}. */
    CompletableFuture<Void> deliver(String to, String subject, String text) {
        String body = JSON.writeValueAsString(Map.of(
                "sender", Map.of("name", "Gym Tracker", "email", from),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "textContent", text));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(TIMEOUT)
                .header("api-key", apiKey)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new MailException("Brevo answered " + response.statusCode() + ": " + response.body());
            }
        });
    }
}
