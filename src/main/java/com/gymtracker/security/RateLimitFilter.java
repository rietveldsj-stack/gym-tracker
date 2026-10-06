package com.gymtracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops password and invite-code guessing. The client IP comes from getRemoteAddr(), which reflects the proxy's
 * X-Forwarded-For because server.forward-headers-strategy=framework.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED = Set.of("/login", "/api/auth/register", "/api/auth/forgot");

    private final AttemptLimiter limiter;

    RateLimitFilter(AttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !LIMITED.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limiter.tryAcquire(request.getRequestURI() + "|" + request.getRemoteAddr())) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many attempts. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
