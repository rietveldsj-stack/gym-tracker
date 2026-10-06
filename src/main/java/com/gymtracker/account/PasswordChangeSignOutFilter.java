package com.gymtracker.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends server sessions that signed in before the account's last password change, so a password reset signs out
 * every device. Each session records when it signed in; sessions from a remember-me login are marked on their
 * first request, which is the moment that login happened.
 */
public class PasswordChangeSignOutFilter extends OncePerRequestFilter {

    static final String SIGNED_IN_AT = PasswordChangeSignOutFilter.class.getName() + ".signedInAt";

    private final AppUserRepository users;
    private final Clock clock;

    public PasswordChangeSignOutFilter(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    /** Call right after a sign-in that created or changed the session. */
    public static void markSignedIn(HttpServletRequest request, Instant at) {
        request.getSession().setAttribute(SIGNED_IN_AT, at);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken || session == null) {
            chain.doFilter(request, response);
            return;
        }
        Instant signedInAt = (Instant) session.getAttribute(SIGNED_IN_AT);
        if (signedInAt == null) {
            session.setAttribute(SIGNED_IN_AT, clock.instant());
            chain.doFilter(request, response);
            return;
        }
        boolean stale = users.findByEmail(auth.getName())
                .map(user -> user.getPasswordChangedAt().isAfter(signedInAt))
                .orElse(true);
        if (stale) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Signed out\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
