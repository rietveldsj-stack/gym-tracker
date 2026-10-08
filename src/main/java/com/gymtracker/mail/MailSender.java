package com.gymtracker.mail;

public interface MailSender {

    /** Sends a plain-text email. May send in the background; otherwise throws {@link MailException} when it fails. */
    void send(String to, String subject, String text);
}
