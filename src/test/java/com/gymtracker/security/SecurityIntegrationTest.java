package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
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
    void meReturnsEmail() throws Exception {
        apiGet("/api/me").andExpect(status().isOk()).andExpect(jsonPath("$.email").value(TestUsers.EMAIL));
    }

    @Test
    void loginSucceedsAndRememberMeCookieSignsInWithoutSession() throws Exception {
        MvcResult login = login(TestUsers.EMAIL, TestUsers.PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie rememberMe = login.getResponse().getCookie("remember-me");
        assertThat(rememberMe).isNotNull();
        assertThat(rememberMe.getMaxAge()).isEqualTo(365 * 24 * 3600);
        assertThat(rememberMe.isHttpOnly()).isTrue();

        mvc.perform(get("/api/me").cookie(rememberMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(TestUsers.EMAIL));
    }

    @Test
    void loginIgnoresCaseAndSpacesInEmail() throws Exception {
        Cookie rememberMe = login("  Tester@Example.COM ", TestUsers.PASSWORD)
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("remember-me");
        mvc.perform(get("/api/me").cookie(rememberMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(TestUsers.EMAIL));
    }

    @Test
    void unknownEmailGetsTheSameMessageAsAWrongPassword() throws Exception {
        login("nobody@example.com", TestUsers.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Wrong email or password"));
    }

    @Test
    void wrongPasswordAnswers401WithMessage() throws Exception {
        login(TestUsers.EMAIL, "nope")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Wrong email or password"));
    }

    @Test
    void loginWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", TestUsers.EMAIL).param("password", TestUsers.PASSWORD))
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

    @Test
    void staticFilesDoNotUseUpTheRememberMeToken() throws Exception {
        // Browsers load the page's files in parallel; if each one ran a remember-me login, the single-use tokens
        // would race and trip Spring's cookie-theft check, signing the user out.
        Cookie rememberMe = login(TestUsers.EMAIL, TestUsers.PASSWORD).andReturn().getResponse().getCookie("remember-me");
        for (String path : new String[] {"/", "/index.html", "/styles.css", "/js/app.js", "/sw.js"}) {
            MvcResult result = mvc.perform(get(path).cookie(rememberMe)).andReturn();
            assertThat(result.getResponse().getCookie("remember-me")).as(path).isNull();
        }
        mvc.perform(get("/api/me").cookie(rememberMe)).andExpect(status().isOk());
    }

    @Test
    void rememberMeLoginHandsOutAFreshCsrfCookie() throws Exception {
        // After a server restart the XSRF cookie survives but the remember-me login rotates the token; the response
        // must carry the new one, or the next write is rejected.
        Cookie rememberMe = login(TestUsers.EMAIL, TestUsers.PASSWORD).andReturn().getResponse().getCookie("remember-me");
        MvcResult result = mvc.perform(get("/api/me").cookie(rememberMe, new Cookie("XSRF-TOKEN", "old-token")))
                .andExpect(status().isOk()).andReturn();
        Cookie[] xsrf = java.util.Arrays.stream(result.getResponse().getCookies())
                .filter(c -> c.getName().equals("XSRF-TOKEN")).toArray(Cookie[]::new);
        assertThat(xsrf).isNotEmpty();
        assertThat(xsrf[xsrf.length - 1].getValue()).isNotEmpty().isNotEqualTo("old-token");
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username).param("password", password));
    }
}
