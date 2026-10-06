package com.gymtracker.account;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** How emails are stored and compared: without surrounding spaces, in lower case. */
    public static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
