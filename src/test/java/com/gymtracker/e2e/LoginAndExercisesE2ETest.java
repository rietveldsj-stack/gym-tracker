package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LoginAndExercisesE2ETest extends E2ETestBase {

    @Test
    void wrongPasswordShowsError() {
        page.navigate("/");
        page.getByLabel("Username").fill("tester");
        page.getByLabel("Password").fill("wrong");
        button("Sign in").click();
        assertThat(page.getByText("Wrong username or password")).isVisible();
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
        page.getByLabel("Name").fill("Back squat");
        button("Save").click();
        assertThat(button("Back squat")).isVisible();

        button("Back squat").click();
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
        Assertions.assertThat(count("select count(*) from exercise")).isZero();

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
}
