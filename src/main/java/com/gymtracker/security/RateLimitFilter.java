package com.gymtracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops password and invite-code guessing. Paths are matched the way Spring matches them (decoded, so /%6cogin
 * counts as /login). The client address comes from getRemoteAddr(): with server.forward-headers-strategy=native,
 * Tomcat takes it from the right end of X-Forwarded-For, which the client can't choose.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, RequestMatcher> LIMITED = Map.of(
            "/login", post("/login"),
            "/api/auth/register", post("/api/auth/register"),
            "/api/auth/forgot", post("/api/auth/forgot"));

    private final AttemptLimiter limiter;

    RateLimitFilter(AttemptLimiter limiter) {
        this.limiter = limiter;
    }

    private static RequestMatcher post(String path) {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path);
    }

    private static String limitedPath(HttpServletRequest request) {
        return LIMITED.entrySet().stream()
                .filter(entry -> entry.getValue().matches(request))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return limitedPath(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limiter.tryAcquire(limitedPath(request) + "|" + request.getRemoteAddr())) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many attempts. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
