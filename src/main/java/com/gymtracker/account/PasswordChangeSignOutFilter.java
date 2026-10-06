package com.gymtracker.account;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;

/**
 * Ends server sessions that signed in before the account's last password change, so a password reset signs out
 * every device. Each session records when it signed in.
 */
public class PasswordChangeSignOutFilter {

    static final String SIGNED_IN_AT = PasswordChangeSignOutFilter.class.getName() + ".signedInAt";

    /** Call right after a sign-in that created or changed the session. */
    public static void markSignedIn(HttpServletRequest request, Instant at) {
        request.getSession().setAttribute(SIGNED_IN_AT, at);
    }
}
