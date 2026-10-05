package com.gymtracker;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTestBase {

    protected static final String XSRF = "test-xsrf-token";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        DbCleaner.clean(jdbc);
    }

    /**
     * A real XSRF-TOKEN cookie plus matching header. Do not use SecurityMockMvcRequestPostProcessors.csrf():
     * it swaps the token repository on the shared CsrfFilter and breaks cookie-based tests that run later.
     */
    protected static RequestPostProcessor xsrf() {
        return request -> {
            Cookie[] existing = request.getCookies() == null ? new Cookie[0] : request.getCookies();
            Cookie[] cookies = Arrays.copyOf(existing, existing.length + 1);
            cookies[existing.length] = new Cookie("XSRF-TOKEN", XSRF);
            request.setCookies(cookies);
            request.addHeader("X-XSRF-TOKEN", XSRF);
            return request;
        };
    }

    /** Signed in as the test user, with a valid CSRF cookie and header. */
    protected static RequestPostProcessor signedIn() {
        return request -> xsrf().postProcessRequest(user("tester").postProcessRequest(request));
    }

    protected ResultActions apiGet(String url) throws Exception {
        return mvc.perform(get(url).with(signedIn()));
    }

    protected ResultActions apiPut(String url, String json) throws Exception {
        return mvc.perform(put(url).with(signedIn()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions apiPost(String url, String json) throws Exception {
        return mvc.perform(post(url).with(signedIn()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions apiDelete(String url) throws Exception {
        return mvc.perform(delete(url).with(signedIn()));
    }
}
