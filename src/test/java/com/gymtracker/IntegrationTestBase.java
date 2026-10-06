package com.gymtracker;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.UUID;
import com.gymtracker.mail.CapturingMailSender;
import com.gymtracker.mail.TestMailConfig;
import com.gymtracker.push.TestPushConfig;
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
@Import({TestcontainersConfiguration.class, TestPushConfig.class, TestMailConfig.class})
public abstract class IntegrationTestBase {

    protected static final String XSRF = "test-xsrf-token";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CapturingMailSender mail;

    @Autowired
    protected com.gymtracker.security.AttemptLimiter attemptLimiter;

    /** The signed-in test account; tests may switch {@link #actingAs} to another account they created. */
    protected UUID testerId;
    protected String actingAs;

    @BeforeEach
    void cleanDatabase() {
        attemptLimiter.reset();
        mail.reset();
        DbCleaner.clean(jdbc);
        testerId = TestUsers.insert(jdbc, TestUsers.EMAIL);
        actingAs = TestUsers.EMAIL;
    }

    protected UUID createUser(String email) {
        return TestUsers.insert(jdbc, email);
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

    /** Signed in as {@link #actingAs}, with a valid CSRF cookie and header. */
    protected RequestPostProcessor signedIn() {
        String email = actingAs;
        return request -> xsrf().postProcessRequest(user(email).postProcessRequest(request));
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

    protected UUID createExercise(String name, String muscleGroup) throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/exercises/" + id, """
                {"name": "%s", "muscleGroup": "%s"}""".formatted(name, muscleGroup)).andExpect(status().isOk());
        return id;
    }

    /** Starts a session whose date is the date part of {@code startedAt} (an ISO instant). */
    protected UUID startSession(String startedAt) throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/sessions/" + id, """
                {"date": "%s", "startedAt": "%s"}""".formatted(startedAt.substring(0, 10), startedAt))
                .andExpect(status().isOk());
        return id;
    }

    protected UUID logSet(UUID sessionId, UUID exerciseId, String weightKg, int reps, String type, String loggedAt)
            throws Exception {
        UUID id = UUID.randomUUID();
        apiPut("/api/sets/" + id, setJson(sessionId, exerciseId, weightKg, reps, type, loggedAt))
                .andExpect(status().isOk());
        return id;
    }

    protected static String setJson(UUID sessionId, UUID exerciseId, String weightKg, int reps, String type,
                                    String loggedAt) {
        return """
                {"sessionId": "%s", "exerciseId": "%s", "weightKg": %s, "reps": %d, "type": "%s", "loggedAt": "%s"}"""
                .formatted(sessionId, exerciseId, weightKg, reps, type, loggedAt);
    }

    protected void endSession(UUID sessionId, String endedAt) throws Exception {
        apiPost("/api/sessions/" + sessionId + "/end", """
                {"endedAt": "%s"}""".formatted(endedAt)).andExpect(status().isOk());
    }
}
