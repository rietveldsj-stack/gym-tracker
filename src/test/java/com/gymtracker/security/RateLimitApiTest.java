package com.gymtracker.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class RateLimitApiTest extends IntegrationTestBase {

    private static final String TOO_MANY = "Too many attempts. Try again later.";

    private ResultActions wrongLogin() throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", TestUsers.EMAIL).param("password", "guess"));
    }

    private ResultActions forgot() throws Exception {
        return mvc.perform(post("/api/auth/forgot").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"nobody@example.com\"}"));
    }

    @Test
    void eleventhLoginWithin15MinutesIsRefused() throws Exception {
        for (int i = 0; i < 10; i++) {
            wrongLogin().andExpect(status().isUnauthorized());
        }
        wrongLogin().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.message").value(TOO_MANY));
        forgot().andExpect(status().isOk()); // counted separately
    }

    @Test
    void eleventhForgotRequestIsRefused() throws Exception {
        for (int i = 0; i < 10; i++) {
            forgot().andExpect(status().isOk());
        }
        forgot().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.message").value(TOO_MANY));
    }

    @Test
    void eleventhRegistrationIsRefused() throws Exception {
        for (int i = 0; i < 11; i++) {
            ResultActions result = mvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\": \"x@example.com\", \"password\": \"long-enough\", \"inviteCode\": \"guess\"}"));
            result.andExpect(i < 10 ? status().isForbidden() : status().isTooManyRequests());
        }
    }

    @Test
    void encodedPathIsCountedToo() throws Exception {
        for (int i = 0; i < 10; i++) {
            wrongLogin().andExpect(status().isUnauthorized());
        }
        mvc.perform(post(java.net.URI.create("/%6cogin")).with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", TestUsers.EMAIL).param("password", "guess"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void forwardedHeadersCannotPickANewClientAddress() throws Exception {
        for (int i = 0; i < 11; i++) {
            ResultActions result = mvc.perform(post("/login").with(xsrf())
                    .header("Forwarded", "for=198.51.100." + i)
                    .header("X-Forwarded-For", "203.0.113." + i)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("username", TestUsers.EMAIL).param("password", "guess"));
            result.andExpect(i < 10 ? status().isUnauthorized() : status().isTooManyRequests());
        }
    }
}
