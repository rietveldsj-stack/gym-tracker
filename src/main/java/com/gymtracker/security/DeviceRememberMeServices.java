package com.gymtracker.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/**
 * Logging out removes only this device's token. Spring's default removes every token of the account, which would
 * also sign out the user's other devices. Works from the cookie, so it also runs when the session had expired.
 */
final class DeviceRememberMeServices extends PersistentTokenBasedRememberMeServices {

    private final JdbcTemplate jdbc;

    DeviceRememberMeServices(String key, UserDetailsService userDetailsService,
                             PersistentTokenRepository tokenRepository, JdbcTemplate jdbc) {
        super(key, userDetailsService, tokenRepository);
        this.jdbc = jdbc;
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        cancelCookie(request, response);
        String cookie = extractRememberMeCookie(request);
        if (cookie == null || cookie.isEmpty()) {
            return;
        }
        try {
            String[] parts = decodeCookie(cookie);
            if (parts.length == 2) {
                jdbc.update("delete from persistent_logins where series = ?", parts[0]);
            }
        } catch (InvalidCookieException ignored) {
            // not a cookie we issued: nothing to delete
        }
    }
}
