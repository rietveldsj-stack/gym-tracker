package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.gymtracker.TestUsers;
import com.microsoft.playwright.BrowserContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LoginAndExercisesE2ETest extends E2ETestBase {

    @Test
    void wrongPasswordShowsError() {
        page.navigate("/");
        page.getByLabel("Email").fill(TestUsers.EMAIL);
        page.getByLabel("Password", new com.microsoft.playwright.Page.GetByLabelOptions().setExact(true)).fill("wrong");
        button("Sign in").click();
        assertThat(page.getByText("Wrong email or password")).isVisible();
    }

    @Test
    void createsExerciseAndRejectsDuplicateName() {
        signIn();
        createExerciseViaUi("Squat", "Quads");
        waitUntilSynced();
        Assertions.assertThat(jdbc.queryForObject("select muscle_group from exercise where name = 'Squat'", String.class))
                .isEqualTo("QUADS");

        page.getByLabel("Add exercise").click();
        page.getByLabel("Name").fill("squat");
        button("Quads").click();
        button("Save").click();
        assertThat(page.getByText("You already have an exercise called 'Squat'")).isVisible();
    }

    @Test
    void editsAndDeletesExercise() {
        signIn();
        createExerciseViaUi("Squat", "Quads");
        button("Squat").click();
        button("Edit").click();
        page.getByLabel("Name").fill("Back squat");
        button("Save").click();
        assertThat(page.locator("h1")).hasText("Back squat");

        button("Edit").click();
        button("Delete exercise").click();
        button("Delete").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from exercise where archived")).isEqualTo(1);
    }

    @Test
    void offlineChangesSyncWhenBackOnline() {
        signIn();
        waitUntilSynced();
        context.setOffline(true);
        createExerciseViaUi("Bench press", "Chest");
        assertThat(page.locator("#syncbar")).hasText("1 change waiting to sync");
        Assertions.assertThat(jdbc.queryForList("select name from exercise", String.class)).isEmpty();

        context.setOffline(false);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from exercise")).isEqualTo(1);
    }

    @Test
    void changesSyncAfterSessionAndCsrfCookiesAreLost() {
        signIn();
        waitUntilSynced();
        context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("XSRF-TOKEN"));
        context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("JSESSIONID"));

        createExerciseViaUi("Deadlift", "Back");
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from exercise")).isEqualTo(1);
        assertThat(page.getByLabel("Password")).hasCount(0);
    }

    @Test
    void staysSignedInWhenServerSessionExpires() {
        signIn();
        waitUntilSynced();
        for (int i = 0; i < 2; i++) {
            context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("JSESSIONID"));
            page.reload();
            assertThat(page.locator("#tabs")).isVisible();
            waitUntilSynced();
        }
        assertThat(page.getByLabel("Password")).hasCount(0);
    }

    @Test
    void tapSurvivesScreenUpdateWhileFingerIsDown() {
        signIn();
        waitUntilSynced();
        tab("Exercises").click();
        com.microsoft.playwright.options.BoundingBox box = page.getByLabel("Add exercise").boundingBox();
        page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
        page.mouse().down();
        // A background change arrives while the user's finger is on the button.
        page.evaluate("() => import('/js/store.js').then((s) => s.dispatch('exercise.put', "
                + "{ id: '00000000-0000-4000-8000-000000000001', name: 'Plank', muscleGroup: 'CORE' }))");
        page.mouse().up();
        assertThat(page.getByLabel("Name")).isVisible();
        assertThat(button("Plank")).isVisible(); // the delayed update still happened
    }

    @Test
    void parallelRequestsAfterSessionLossKeepHerSignedIn() {
        signIn();
        for (int i = 0; i < 2; i++) {
            context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("JSESSIONID"));
            // e.g. saving a set with alerts on: sync and the rest timer call the API at the same moment
            page.evaluate("() => import('/js/api.js').then((api) => Promise.all(["
                    + "api.request('GET', '/api/exercises'), api.request('GET', '/api/sessions'), api.request('GET', '/api/me')]))");
        }
        context.clearCookies(new BrowserContext.ClearCookiesOptions().setName("JSESSIONID"));
        page.reload();
        assertThat(page.locator("#tabs")).isVisible();
        waitUntilSynced();
        assertThat(page.getByLabel("Password")).hasCount(0);
    }

    @Test
    void opensStraightAwayOnWeakSignal() {
        signIn();
        // The server doesn't answer at all, like one bar of signal in a basement gym.
        page.addInitScript("const realFetch = window.fetch; window.fetch = (url, options) => "
                + "String(url).includes('/api/') ? new Promise(() => {}) : realFetch(url, options);");
        page.reload();
        assertThat(button("Start session")).isVisible(new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(3000));
    }

    @Test
    void loginFormIsNotWipedWhileChangesWait() {
        signIn();
        context.setOffline(true);
        createExerciseViaUi("Plank", "Core");
        context.clearCookies();
        context.setOffline(false);
        page.reload();
        page.getByLabel("Email").fill(TestUsers.EMAIL);
        page.evaluate("() => window.dispatchEvent(new Event('online'))"); // a background sync attempt
        page.evaluate("() => new Promise((resolve) => setTimeout(resolve, 500))");
        assertThat(page.getByLabel("Email")).hasValue(TestUsers.EMAIL);
    }
}
