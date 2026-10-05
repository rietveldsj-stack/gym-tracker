package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class SecurityIntegrationTest extends IntegrationTestBase {

    @Test
    void apiRequiresSignInAndAnswers401WithCsrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
    }

    @Test
    void meReturnsUsername() throws Exception {
        apiGet("/api/me").andExpect(status().isOk()).andExpect(jsonPath("$.username").value("tester"));
    }

    @Test
    void loginSucceedsAndRememberMeCookieSignsInWithoutSession() throws Exception {
        MvcResult login = login("tester", "secret-pass").andExpect(status().isOk()).andReturn();
        Cookie rememberMe = login.getResponse().getCookie("remember-me");
        assertThat(rememberMe).isNotNull();
        assertThat(rememberMe.getMaxAge()).isEqualTo(365 * 24 * 3600);
        assertThat(rememberMe.isHttpOnly()).isTrue();

        mvc.perform(get("/api/me").cookie(rememberMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("tester"));
    }

    @Test
    void wrongPasswordAnswers401WithMessage() throws Exception {
        login("tester", "nope")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Wrong username or password"));
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "tester").param("password", "secret-pass"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(put("/api/me").with(user("tester"))).andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username).param("password", password));
    }
}
