package com.gymtracker.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailConfigTest {

    private static final String URL = "https://api.brevo.com/v3/smtp/email";

    @Test
    void logsInsteadOfSendingWhenBrevoIsNotConfigured() {
        assertThat(new MailConfig().mailSender("", "sender@example.com", URL)).isInstanceOf(LoggingMailSender.class);
        assertThat(new MailConfig().mailSender("key", " ", URL)).isInstanceOf(LoggingMailSender.class);
    }

    @Test
    void usesBrevoWhenConfigured() {
        assertThat(new MailConfig().mailSender("key", "sender@example.com", URL)).isInstanceOf(BrevoMailSender.class);
    }
}
