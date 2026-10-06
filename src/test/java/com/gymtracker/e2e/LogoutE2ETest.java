package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.gymtracker.TestUsers;
import com.microsoft.playwright.Page;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class LogoutE2ETest extends E2ETestBase {

    private void openLogout() {
        tab("Workout").click();
        page.getByLabel("Settings").click();
        assertThat(page.getByText("Signed in as " + TestUsers.EMAIL)).isVisible();
        button("Log out").click();
    }

    private void signInAs(String email) {
        page.getByLabel("Email").fill(email);
        page.getByLabel("Password", new Page.GetByLabelOptions().setExact(true)).fill(TestUsers.PASSWORD);
        button("Sign in").click();
        waitUntilSynced();
    }

    @Test
    void logOutAndTheNextAccountStartsEmpty() {
        com.gymtracker.TestUsers.insert(jdbc, "other@example.com");
        signIn();
        createExerciseViaUi("Squat", "Quads");
        waitUntilSynced();

        openLogout();
        assertThat(page.getByText("You'll need your email and password to sign in again.")).isVisible();
        button("Log out").click();
        assertThat(page.getByLabel("Email")).isVisible();
        Assertions.assertThat(count("select count(*) from persistent_logins")).isZero();

        page.reload(); // stays signed out
        assertThat(page.getByLabel("Email")).isVisible();

        signInAs("other@example.com");
        tab("Exercises").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        tab("Workout").click();
        page.getByLabel("Settings").click();
        button("Log out").click();
        button("Log out").click();

        signInAs(TestUsers.EMAIL);
        tab("Exercises").click();
        assertThat(button("Squat")).isVisible();
    }

    @Test
    void warnsAboutChangesThatHaveNotSynced() {
        signIn();
        context.setOffline(true);
        createExerciseViaUi("Plank", "Core");
        openLogout();
        assertThat(page.getByText("1 change hasn't synced yet and will be lost.")).isVisible();
        context.setOffline(false);
        button("Try again").click();
        assertThat(page.getByText("You'll need your email and password to sign in again.")).isVisible();
        Assertions.assertThat(count("select count(*) from exercise")).isEqualTo(1);
    }

    @Test
    void offlineLogoutKeepsYouSignedIn() {
        signIn();
        context.setOffline(true);
        openLogout();
        button("Log out").click();
        assertThat(page.getByText("You're offline. Connect to the internet to log out.")).isVisible();
        context.setOffline(false);
        assertThat(page.getByLabel("Email")).hasCount(0);
        Assertions.assertThat(count("select count(*) from persistent_logins")).isEqualTo(1);
    }

    @Test
    void refusedLogoutKeepsYouSignedIn() {
        // The server refuses the logout (e.g. a rejected CSRF token). page.route can't do this: the service worker
        // sits in between, so the page's fetch is replaced instead.
        page.addInitScript("const realFetch = window.fetch; window.fetch = (url, options) => String(url).endsWith('/logout') "
                + "? Promise.resolve(new Response('', { status: 403 })) : realFetch(url, options);");
        signIn();
        openLogout();
        button("Log out").click();
        assertThat(page.getByText("Could not log out. Try again.")).isVisible();
        assertThat(page.getByLabel("Email")).hasCount(0);
    }
}
