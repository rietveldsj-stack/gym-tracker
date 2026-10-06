package com.gymtracker.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record RegisterRequest(String email, String password, String inviteCode) {
    }

    private final AccountService accounts;
    private final UserDetailsService userDetails;
    private final RememberMeServices rememberMe;
    private final SecurityContextRepository contextRepository;
    private final Clock clock;

    AuthController(AccountService accounts, UserDetailsService userDetails, RememberMeServices rememberMe,
                   SecurityContextRepository contextRepository, Clock clock) {
        this.accounts = accounts;
        this.userDetails = userDetails;
        this.rememberMe = rememberMe;
        this.contextRepository = contextRepository;
        this.clock = clock;
    }

    /** Creates the account and signs it in exactly like a successful POST /login would. */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> register(@RequestBody RegisterRequest body, HttpServletRequest request,
                                 HttpServletResponse response) {
        AppUser user = accounts.register(body.email(), body.password(), body.inviteCode());
        UserDetails details = userDetails.loadUserByUsername(user.getEmail());
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation protection, as form login does
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        PasswordChangeSignOutFilter.markSignedIn(request, clock.instant());
        rememberMe.loginSuccess(request, response, auth);
        return Map.of("email", user.getEmail());
    }
}
