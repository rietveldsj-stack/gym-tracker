package com.gymtracker.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** {@code email} must already be normalized with {@link Emails#normalize}. */
    Optional<AppUser> findByEmail(String email);
}
