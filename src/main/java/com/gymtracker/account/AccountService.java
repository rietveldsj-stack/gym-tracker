package com.gymtracker.account;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.gymtracker.common.BadRequestException;
import com.gymtracker.common.ConflictException;
import com.gymtracker.common.ForbiddenException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL = 254;
    private static final int MIN_PASSWORD_CHARS = 8;
    private static final int MAX_PASSWORD_BYTES = 72; // BCrypt ignores everything after 72 bytes

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String inviteCode;

    public AccountService(AppUserRepository users, PasswordEncoder encoder, Clock clock,
                          @Value("${app.invite-code}") String inviteCode) {
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
        this.inviteCode = inviteCode == null ? "" : inviteCode.strip();
    }

    public AppUser register(String rawEmail, String password, String code) {
        if (inviteCode.isEmpty()) {
            throw new ForbiddenException("Registration is closed");
        }
        String given = code == null ? "" : code.strip();
        if (!MessageDigest.isEqual(given.getBytes(UTF_8), inviteCode.getBytes(UTF_8))) {
            throw new ForbiddenException("Wrong invite code");
        }
        String email = Emails.normalize(rawEmail);
        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            throw new BadRequestException("Enter a valid email address");
        }
        validatePassword(password);
        if (users.findByEmail(email).isPresent()) {
            throw new ConflictException("An account with this email already exists");
        }
        return users.save(new AppUser(UUID.randomUUID(), email, encoder.encode(password), clock.instant()));
    }

    static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_CHARS) {
            throw new BadRequestException("Use at least 8 characters for your password");
        }
        if (password.getBytes(UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("Use at most 72 characters for your password");
        }
    }
}
