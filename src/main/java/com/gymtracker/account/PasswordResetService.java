package com.gymtracker.account;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.gymtracker.common.BadRequestException;
import com.gymtracker.mail.MailSender;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PasswordResetService {

    static final String SUBJECT = "Reset your Gym Tracker password";
    static final String EXPIRED = "This link has expired. Request a new one.";
    private static final Duration VALIDITY = Duration.ofHours(1);
    private static final int MAX_EMAILS_PER_HOUR = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AppUserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PersistentTokenRepository rememberMeTokens;
    private final PasswordEncoder encoder;
    private final MailSender mail;
    private final Clock clock;
    private final String baseUrl;

    public PasswordResetService(AppUserRepository users, PasswordResetTokenRepository tokens,
                                PersistentTokenRepository rememberMeTokens, PasswordEncoder encoder, MailSender mail,
                                Clock clock, @Value("${app.base-url}") String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("APP_BASE_URL must be set (the app's public URL, used in reset links)");
        }
        this.users = users;
        this.tokens = tokens;
        this.rememberMeTokens = rememberMeTokens;
        this.encoder = encoder;
        this.mail = mail;
        this.clock = clock;
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /** Emails a reset link when the account exists. Callers answer the same way whatever happens here. */
    public void requestReset(String rawEmail) {
        Optional<AppUser> found = users.findByEmail(Emails.normalize(rawEmail));
        if (found.isEmpty()) {
            return;
        }
        AppUser user = found.get();
        Instant now = clock.instant();
        if (tokens.countByUserIdAndCreatedAtAfter(user.getId(), now.minus(Duration.ofHours(1))) >= MAX_EMAILS_PER_HOUR) {
            log.info("Reset email limit reached for an account");
            return;
        }
        String token = newToken();
        tokens.save(new PasswordResetToken(UUID.randomUUID(), user.getId(), sha256(token), now, now.plus(VALIDITY)));
        try {
            mail.send(user.getEmail(), SUBJECT, """
                    Someone asked to reset the password for your Gym Tracker account.

                    Choose a new password here:
                    %s/#/reset?token=%s

                    This link works once and expires in 1 hour. If you didn't ask for this, ignore this email.
                    """.formatted(baseUrl, token));
        } catch (RuntimeException e) {
            log.warn("Sending the reset email failed: {}", e.getMessage()); // never log the token
        }
    }

    /** Sets the new password and signs the account out everywhere. Returns the account's email. */
    public String reset(String token, String newPassword) {
        Instant now = clock.instant();
        PasswordResetToken found = tokens.findByTokenHash(sha256(token == null ? "" : token))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new BadRequestException(EXPIRED));
        AccountService.validatePassword(newPassword); // before using the link, so a too-short password doesn't burn it
        AppUser user = users.findById(found.getUserId()).orElseThrow(() -> new BadRequestException(EXPIRED));
        user.changePassword(encoder.encode(newPassword), now);
        tokens.useAllOpen(user.getId(), now);
        rememberMeTokens.removeUserTokens(user.getEmail());
        return user.getEmail();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
