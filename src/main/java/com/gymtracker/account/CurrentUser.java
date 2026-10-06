package com.gymtracker.account;

import com.gymtracker.common.NotSignedInException;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The id of the account making the current request. Controllers pass it to every service call on owned data. */
@Component
public class CurrentUser {

    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new NotSignedInException();
        }
        return users.findByEmail(auth.getName()).map(AppUser::getId).orElseThrow(NotSignedInException::new);
    }
}
