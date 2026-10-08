package com.gymtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrevoMailSenderTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 201;
    private volatile long delayMs = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/smtp/email", exchange -> {
            method.set(exchange.getRequestMethod());
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private BrevoMailSender sender() {
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email");
        return new BrevoMailSender(url, "test-key", "sender@example.com");
    }

    @Test
    void postsTheEmailToBrevo() {
        sender().deliver("someone@example.com", "Reset your Gym Tracker password", "Line 1\nhttps://x/#/reset?token=abc").join();
        assertThat(method.get()).isEqualTo("POST");
        assertThat(apiKey.get()).isEqualTo("test-key");
        assertThat(body.get())
                .contains("\"sender\":{")
                .contains("\"email\":\"sender@example.com\"")
                .contains("\"to\":[{\"email\":\"someone@example.com\"}]")
                .contains("\"subject\":\"Reset your Gym Tracker password\"")
                .contains("\"textContent\":\"Line 1\\nhttps://x/#/reset?token=abc\"");
    }

    @Test
    void failsWhenBrevoRefuses() {
        status = 401;
        assertThatThrownBy(() -> sender().deliver("someone@example.com", "s", "t").join())
                .hasCauseInstanceOf(MailException.class)
                .hasMessageContaining("401");
    }

    /** A reset request must answer as fast for an account that exists (email sent) as for one that doesn't. */
    @Test
    void sendReturnsWithoutWaitingForBrevo() {
        delayMs = 2000;
        long start = System.nanoTime();
        sender().send("someone@example.com", "s", "t");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofMillis(500));
    }

    @Test
    void sendDoesNotThrowWhenBrevoRefuses() throws InterruptedException {
        status = 500;
        sender().send("someone@example.com", "s", "t"); // logged in the background instead
        Thread.sleep(200);
        assertThat(method.get()).isEqualTo("POST");
    }
}
