package com.gymtracker.mail;

public interface MailSender {

    /** Sends a plain-text email. Throws {@link MailException} when it could not be sent. */
    void send(String to, String subject, String text);
}
