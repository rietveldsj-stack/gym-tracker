package com.gymtracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stops password and invite-code guessing. Paths are matched the way Spring matches them (decoded, so /%6cogin
 * counts as /login). The client address comes from getRemoteAddr(): with server.forward-headers-strategy=native,
 * Tomcat takes it from the right end of X-Forwarded-For, which the client can't choose. That needs Tomcat to trust
 * the proxy in front (on Railway, an address in 100.64.0.0/10, which Tomcat trusts by default). If the log below ever
 * shows such an internal address, every visitor shares one limit and server.tomcat.remoteip.internal-proxies needs
 * setting.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

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
        String path = limitedPath(request);
        if (!limiter.tryAcquire(path + "|" + request.getRemoteAddr())) {
            log.warn("Too many attempts on {} from {}", path, request.getRemoteAddr());
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Too many attempts. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
