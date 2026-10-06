package com.gymtracker.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class PasswordResetApiTest extends IntegrationTestBase {

    private static final String SENT = "If an account exists for this email, we've sent a reset link.";
    private static final String EXPIRED = "This link has expired. Request a new one.";
    private static final Pattern LINK = Pattern.compile("(http://localhost:8080/#/reset\\?token=)([A-Za-z0-9_-]+)");

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/auth/forgot").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\"}".formatted(email)));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/reset").with(xsrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\", \"password\": \"%s\"}".formatted(token, password)));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", TestUsers.EMAIL).param("password", password));
    }

    private String lastToken() {
        Matcher matcher = LINK.matcher(mail.mails().getLast().text());
        assertThat(matcher.find()).as("reset link in the email").isTrue();
        return matcher.group(2);
    }

    @Test
    void emailedLinkSetsANewPassword() throws Exception {
        forgot(" Tester@Example.com ").andExpect(status().isOk()).andExpect(jsonPath("$.message").value(SENT));
        assertThat(mail.mails()).hasSize(1);
        assertThat(mail.mails().getFirst().to()).isEqualTo(TestUsers.EMAIL);
        assertThat(mail.mails().getFirst().subject()).isEqualTo("Reset your Gym Tracker password");
        assertThat(mail.mails().getFirst().text()).contains("expires in 1 hour");

        reset(lastToken(), "brand-new-pass").andExpect(status().isOk()).andExpect(jsonPath("$.email").value(TestUsers.EMAIL));
        login("brand-new-pass").andExpect(status().isOk());
        login(TestUsers.PASSWORD).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select token_hash from password_reset_token", String.class))
                .hasSize(64).doesNotContain(lastToken());
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        String known = forgot(TestUsers.EMAIL).andReturn().getResponse().getContentAsString();
        String unknown = forgot("nobody@example.com").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(unknown).isEqualTo(known);
        assertThat(mail.mails()).hasSize(1);
    }

    @Test
    void linkWorksOnce() throws Exception {
        forgot(TestUsers.EMAIL);
        String token = lastToken();
        reset(token, "brand-new-pass").andExpect(status().isOk());
        reset(token, "another-pass-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
    }

    @Test
    void expiredLinkIsRefused() throws Exception {
        forgot(TestUsers.EMAIL);
        jdbc.update("update password_reset_token set expires_at = now() - interval '1 minute'");
        reset(lastToken(), "brand-new-pass").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
    }

    @Test
    void unknownTokenIsRefused() throws Exception {
        reset("made-up-token", "brand-new-pass").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(EXPIRED));
    }

    @Test
    void usingOneLinkCancelsTheOthers() throws Exception {
        forgot(TestUsers.EMAIL);
        String first = lastToken();
        forgot(TestUsers.EMAIL);
        reset(lastToken(), "brand-new-pass").andExpect(status().isOk());
        reset(first, "another-pass-1").andExpect(status().isBadRequest());
    }

    @Test
    void tooShortPasswordKeepsTheLinkUsable() throws Exception {
        forgot(TestUsers.EMAIL);
        reset(lastToken(), "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Use at least 8 characters for your password"));
        reset(lastToken(), "brand-new-pass").andExpect(status().isOk());
    }

    @Test
    void atMostThreeEmailsPerHour() throws Exception {
        for (int i = 0; i < 4; i++) {
            forgot(TestUsers.EMAIL).andExpect(status().isOk()).andExpect(jsonPath("$.message").value(SENT));
        }
        assertThat(mail.mails()).hasSize(3);
    }

    @Test
    void failedEmailGivesTheSameAnswer() throws Exception {
        mail.failNext();
        forgot(TestUsers.EMAIL).andExpect(status().isOk()).andExpect(jsonPath("$.message").value(SENT));
    }

    @Test
    void resetSignsOutEveryDevice() throws Exception {
        MvcResult phone = login(TestUsers.PASSWORD).andReturn();
        Cookie rememberMe = phone.getResponse().getCookie("remember-me");
        MockHttpSession session = (MockHttpSession) phone.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());

        forgot(TestUsers.EMAIL);
        reset(lastToken(), "brand-new-pass").andExpect(status().isOk());

        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(rememberMe)).andExpect(status().isUnauthorized());
        MvcResult again = login("brand-new-pass").andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/me").session((MockHttpSession) again.getRequest().getSession(false)))
                .andExpect(status().isOk());
    }
}
