package com.gymtracker.mail;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MailConfig {

    @Bean
    MailSender mailSender(@Value("${app.mail.brevo-api-key}") String apiKey,
                          @Value("${app.mail.from}") String from,
                          @Value("${app.mail.brevo-url}") String url) {
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()) {
            return new LoggingMailSender();
        }
        return new BrevoMailSender(URI.create(url), apiKey.strip(), from.strip());
    }
}
