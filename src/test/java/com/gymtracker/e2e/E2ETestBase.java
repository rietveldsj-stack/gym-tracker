package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.gymtracker.DbCleaner;
import com.gymtracker.TestcontainersConfiguration;
import com.gymtracker.push.TestPushConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Runs the real app on a random port and drives it with WebKit (Safari's engine) at iPhone size. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestPushConfig.class})
public abstract class E2ETestBase {

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    int port;

    @Autowired
    protected JdbcTemplate jdbc;

    protected BrowserContext context;
    protected Page page;
    protected final List<String> pageErrors = new ArrayList<>();
    protected UUID testerId;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.webkit().launch();
    }

    @AfterAll
    static void closeBrowser() {
        playwright.close();
    }

    @BeforeEach
    void openPage() {
        DbCleaner.clean(jdbc);
        testerId = com.gymtracker.TestUsers.insert(jdbc, com.gymtracker.TestUsers.EMAIL);
        context = browser.newContext(new Browser.NewContextOptions()
                .setBaseURL("http://localhost:" + port)
                .setViewportSize(390, 844)
                .setDeviceScaleFactor(3)
                .setIsMobile(true)
                .setHasTouch(true)
                .setLocale("en-GB")
                .setTimezoneId("Europe/Amsterdam"));
        page = context.newPage();
        page.onPageError(pageErrors::add);
    }

    @AfterEach
    void closePage() {
        // Let a sync that is still running finish, so its writes can't land in the next test's clean database.
        try {
            page.waitForFunction("() => document.body.dataset.sync !== 'syncing'", null,
                    new Page.WaitForFunctionOptions().setTimeout(10_000));
        } catch (RuntimeException ignored) {
            // offline or stuck on purpose in this test: nothing will be written
        }
        context.close();
        org.assertj.core.api.Assertions.assertThat(pageErrors).as("uncaught errors in the page").isEmpty();
    }

    protected void signIn() {
        page.navigate("/");
        page.getByLabel("Email").fill(com.gymtracker.TestUsers.EMAIL);
        page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(com.gymtracker.TestUsers.PASSWORD);
        button("Sign in").click();
        assertThat(page.locator("#tabs")).isVisible();
        waitUntilSynced(); // tests start from loaded data; slow-load behaviour has its own tests
    }

    protected Locator button(String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    protected Locator tab(String name) {
        return page.locator("#tabs").getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(name));
    }

    protected void createExerciseViaUi(String name, String muscleLabel) {
        tab("Exercises").click();
        page.getByLabel("Add exercise").click();
        page.getByLabel("Name").fill(name);
        button(muscleLabel).click();
        button("Save").click();
        assertThat(button(name)).isVisible();
    }

    /** Waits until the outbox is empty and the snapshot has been reloaded from the server. */
    protected void waitUntilSynced() {
        assertThat(page.locator("body[data-sync='idle']"))
                .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(20_000));
        assertThat(page.locator("#syncbar")).isHidden();
    }

    protected int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    protected UUID seedExercise(String name, String muscleGroup) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exercise (id, name, muscle_group, archived, created_at) values (?, ?, ?, false, now())",
                id, name, muscleGroup);
        return id;
    }

    protected UUID seedEndedSession(String date, String startedAt, String endedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into workout_session (id, session_date, started_at, ended_at) "
                + "values (?, ?::date, ?::timestamptz, ?::timestamptz)", id, date, startedAt, endedAt);
        return id;
    }

    protected void seedSet(UUID sessionId, UUID exerciseId, String weightKg, int reps, String type, String loggedAt) {
        jdbc.update("insert into workout_set (id, session_id, exercise_id, weight_kg, reps, type, logged_at) "
                        + "values (?, ?, ?, ?::numeric, ?, ?, ?::timestamptz)",
                UUID.randomUUID(), sessionId, exerciseId, weightKg, reps, type, loggedAt);
    }
}
