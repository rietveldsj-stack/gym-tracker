package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class RegistrationApiTest extends IntegrationTestBase {

    private ResultActions register(String email, String password, String inviteCode) throws Exception {
        return mvc.perform(post("/api/auth/register").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "inviteCode": "%s"}""".formatted(email, password, inviteCode)));
    }

    private int accounts() {
        return jdbc.queryForObject("select count(*) from app_user", Integer.class);
    }

    @Test
    void registersAndSignsIn() throws Exception {
        MvcResult result = register("new@example.com", "long-enough", "test-invite")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andReturn();
        Cookie rememberMe = result.getResponse().getCookie("remember-me");
        assertThat(rememberMe).isNotNull();
        mvc.perform(get("/api/me").cookie(rememberMe)).andExpect(jsonPath("$.email").value("new@example.com"));
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session)).andExpect(jsonPath("$.email").value("new@example.com"));
        assertThat(jdbc.queryForObject("select password_hash from app_user where email = 'new@example.com'", String.class))
                .startsWith("$2").doesNotContain("long-enough");
    }

    @Test
    void storesTheEmailTrimmedAndLowercased() throws Exception {
        register("  New@Example.COM ", "long-enough", "test-invite").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select email from app_user where email like 'new%'", String.class))
                .isEqualTo("new@example.com");
    }

    @Test
    void newAccountStartsEmpty() throws Exception {
        createExercise("Squat", "QUADS"); // the tester's
        MvcResult result = register("new@example.com", "long-enough", "test-invite").andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/exercises").session(session)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void wrongInviteCodeIsRefused() throws Exception {
        register("new@example.com", "long-enough", "guess")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Wrong invite code"));
        assertThat(accounts()).isEqualTo(1);
    }

    @Test
    void emailThatIsTakenIgnoringCaseIsRefused() throws Exception {
        register("TESTER@example.com", "long-enough", "test-invite")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists"));
    }

    @Test
    void passwordMustBe8To72Characters() throws Exception {
        register("new@example.com", "short", "test-invite")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use at least 8 characters for your password"));
        register("new@example.com", "a".repeat(73), "test-invite")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use at most 72 characters for your password"));
        assertThat(accounts()).isEqualTo(1);
    }

    @Test
    void emailMustLookLikeAnEmail() throws Exception {
        register("not-an-email", "long-enough", "test-invite")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a valid email address"));
    }

    @Test
    void registerNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"new@example.com\", \"password\": \"long-enough\", \"inviteCode\": \"test-invite\"}"))
                .andExpect(status().isForbidden());
        assertThat(accounts()).isEqualTo(1);
    }

    @Test
    void testerCanStillSignIn() throws Exception {
        mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", TestUsers.EMAIL).param("password", TestUsers.PASSWORD))
                .andExpect(status().isOk());
    }
}
