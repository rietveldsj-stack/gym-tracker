package com.gymtracker.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    void useAllOpen(UUID userId, Instant now);
}
