package com.gymtracker.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps sent emails in memory so tests can read reset links. */
public class CapturingMailSender implements MailSender {

    public record Mail(String to, String subject, String text) {
    }

    private final List<Mail> mails = new CopyOnWriteArrayList<>();
    private volatile boolean failNext;

    @Override
    public void send(String to, String subject, String text) {
        if (failNext) {
            failNext = false;
            throw new MailException("Simulated failure");
        }
        mails.add(new Mail(to, subject, text));
    }

    public List<Mail> mails() {
        return List.copyOf(mails);
    }

    public void failNext() {
        failNext = true;
    }

    public void reset() {
        mails.clear();
        failNext = false;
    }
}
