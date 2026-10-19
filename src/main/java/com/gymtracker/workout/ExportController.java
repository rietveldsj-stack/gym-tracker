package com.gymtracker.workout;

import com.gymtracker.account.AppUserRepository;
import com.gymtracker.common.BadRequestException;
import com.gymtracker.common.NotFoundException;
import com.gymtracker.common.NotSignedInException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only list of one account's finished sessions, for Cadence's Workouts goal. It has its own key instead of a
 * sign-in: EXPORT_KEY, and only the account EXPORT_USER_EMAIL is shared. Without a key the endpoint does not exist.
 */
@RestController
class ExportController {

    record ExportedSession(Instant startedAt, Instant endedAt) {
    }

    private static final Duration MAX_RANGE = Duration.ofDays(62);

    private final WorkoutSessionRepository sessions;
    private final AppUserRepository users;
    private final String key;
    private final String userEmail;

    ExportController(WorkoutSessionRepository sessions, AppUserRepository users,
                     @Value("${app.export.key:}") String key, @Value("${app.export.user-email:}") String userEmail) {
        this.sessions = sessions;
        this.users = users;
        this.key = key;
        this.userEmail = userEmail;
    }

    @GetMapping("/api/export/sessions")
    List<ExportedSession> sessions(@RequestHeader(name = "Authorization", required = false) String authorization,
                                   @RequestParam Instant from, @RequestParam Instant to) {
        if (key == null || key.isBlank()) {
            throw new NotFoundException("Not found");
        }
        String given = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            throw new NotSignedInException();
        }
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BadRequestException("from must be before to, at most 62 days apart");
        }
        return users.findByEmail(userEmail.strip().toLowerCase())
                .map(user -> sessions.findByUserIdAndEndedAtIsNotNullAndStartedAtGreaterThanEqualAndStartedAtLessThan(
                        user.getId(), from, to))
                .orElse(List.of())
                .stream()
                .map(session -> new ExportedSession(session.getStartedAt(), session.getEndedAt()))
                .toList();
    }
}
