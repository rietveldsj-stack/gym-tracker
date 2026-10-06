package com.gymtracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import com.gymtracker.TestUsers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

class LogoutApiTest extends IntegrationTestBase {

    private MvcResult login() throws Exception {
        return mvc.perform(post("/login").with(xsrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", TestUsers.EMAIL).param("password", TestUsers.PASSWORD)).andReturn();
    }

    @Test
    void logoutSignsOutOnlyThisDevice() throws Exception {
        Cookie phone = login().getResponse().getCookie("remember-me");
        Cookie laptop = login().getResponse().getCookie("remember-me");

        MvcResult logout = mvc.perform(post("/logout").with(xsrf()).cookie(phone))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(logout.getResponse().getCookie("remember-me").getMaxAge()).isZero();

        mvc.perform(get("/api/me").cookie(phone)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").cookie(laptop)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins", Integer.class)).isEqualTo(1);
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = (MockHttpSession) login().getRequest().getSession(false);
        mvc.perform(post("/logout").with(xsrf()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
    }

    @Test
    void logoutWhenAlreadySignedOutIsFine() throws Exception {
        mvc.perform(post("/logout").with(xsrf())).andExpect(status().isNoContent());
    }
}
