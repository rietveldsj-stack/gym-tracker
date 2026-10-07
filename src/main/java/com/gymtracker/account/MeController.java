package com.gymtracker.account;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
class MeController {

    /** {@code name} is left out for accounts made before names existed. */
    record Me(String email, String name) {
        static Me of(AppUser user) {
            return new Me(user.getEmail(), user.getName());
        }
    }

    record NameRequest(String name) {
    }

    private final AppUserRepository users;
    private final AccountService accounts;
    private final CurrentUser currentUser;

    MeController(AppUserRepository users, AccountService accounts, CurrentUser currentUser) {
        this.users = users;
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping
    Me me() {
        return Me.of(users.findById(currentUser.id()).orElseThrow());
    }

    @PutMapping("/name")
    Me changeName(@RequestBody NameRequest body) {
        return Me.of(accounts.changeName(currentUser.id(), body.name()));
    }
}
