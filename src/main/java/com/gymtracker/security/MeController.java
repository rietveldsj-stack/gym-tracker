package com.gymtracker.security;

import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    @GetMapping("/api/me")
    Map<String, String> me(Principal principal) {
        return Map.of("email", principal.getName());
    }
}
