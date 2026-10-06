package com.gymtracker.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when Brevo isn't configured. Logs who would have got an email, never its content (it may hold a reset link). */
class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    @Override
    public void send(String to, String subject, String text) {
        log.warn("Email not configured (BREVO_API_KEY, MAIL_FROM): not sending '{}' to {}", subject, to);
    }
}
