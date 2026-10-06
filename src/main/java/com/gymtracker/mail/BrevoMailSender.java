package com.gymtracker.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Sends through Brevo's transactional email API over HTTPS (no SMTP, so hosting port rules don't matter). */
class BrevoMailSender implements MailSender {

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

    @Override
    public void send(String to, String subject, String text) {
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
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new MailException("Brevo answered " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new MailException("Sending email failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailException("Sending email was interrupted", e);
        }
    }
}
