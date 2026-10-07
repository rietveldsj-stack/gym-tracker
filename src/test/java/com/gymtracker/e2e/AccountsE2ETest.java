package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.gymtracker.TestUsers;
import com.microsoft.playwright.Page;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class AccountsE2ETest extends E2ETestBase {

    private void fillPassword(String label, String value) {
        page.getByLabel(label, new Page.GetByLabelOptions().setExact(true)).fill(value);
    }

    @Test
    void createsAnAccountWithTheInviteCode() {
        page.navigate("/");
        button("Create account").click();
        page.getByLabel("Your name").fill("Janet");
        page.getByLabel("Email").fill("new@example.com");
        fillPassword("Password", "long-enough");
        page.getByLabel("Invite code").fill("test-invite");
        button("Create account").click();
        assertThat(page.locator("#tabs")).isVisible();
        waitUntilSynced();
        assertThat(page.locator("h1")).containsText(", Janet");
        Assertions.assertThat(count("select count(*) from app_user where email = 'new@example.com'")).isEqualTo(1);
    }

    @Test
    void wrongInviteCodeShowsTheServersMessage() {
        page.navigate("/");
        button("Create account").click();
        page.getByLabel("Your name").fill("Janet");
        page.getByLabel("Email").fill("new@example.com");
        fillPassword("Password", "long-enough");
        page.getByLabel("Invite code").fill("guess");
        button("Create account").click();
        assertThat(page.getByText("Wrong invite code")).isVisible();
    }

    @Test
    void resetsAForgottenPasswordFromTheEmailLink() {
        page.navigate("/");
        button("Forgot password?").click();
        page.getByLabel("Email").fill(TestUsers.EMAIL);
        button("Send reset link").click();
        assertThat(page.getByText("If an account exists for this email")).isVisible();

        Matcher link = Pattern.compile("#/reset\\?token=([A-Za-z0-9_-]+)").matcher(mail.mails().getLast().text());
        Assertions.assertThat(link.find()).isTrue();
        page.navigate("about:blank");
        page.navigate("/#/reset?token=" + link.group(1));
        fillPassword("New password", "brand-new-pass");
        button("Change password").click();

        assertThat(page.getByText("Password changed. Sign in.")).isVisible();
        assertThat(page.getByLabel("Email")).hasValue(TestUsers.EMAIL);
        Assertions.assertThat(page.url()).doesNotContain("token"); // the token doesn't stay in the address bar
        fillPassword("Password", "brand-new-pass");
        button("Sign in").click();
        assertThat(page.locator("#tabs")).isVisible();
    }

    @Test
    void switchingAccountsWipesThePreviousAccountsData() {
        com.gymtracker.TestUsers.insert(jdbc, "other@example.com");
        signIn();
        createExerciseViaUi("Squat", "Quads");
        waitUntilSynced();

        context.setOffline(true);
        createExerciseViaUi("Plank", "Core"); // waits on the phone, unsent
        context.clearCookies(); // e.g. signed out elsewhere; the phone still has the tester's data
        context.setOffline(false);
        page.reload();
        page.getByLabel("Email").fill("other@example.com");
        fillPassword("Password", TestUsers.PASSWORD);
        button("Sign in").click();
        waitUntilSynced();
        tab("Exercises").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        Assertions.assertThat(jdbc.queryForList("select name from exercise", String.class))
                .containsExactly("Squat"); // the tester's unsent Plank never reached the other account
    }

    @Test
    void oldDataFromBeforeAccountsIsNotUploaded() {
        page.navigate("/");
        // What a phone has after using the single-user version: data and a pending change, but no account tag.
        page.evaluate("() => { localStorage.setItem('gt.knownUser', '1'); "
                + "localStorage.setItem('gt.outbox.v1', JSON.stringify([{ opId: 'o1', kind: 'exercise.put', "
                + "payload: { id: '00000000-0000-4000-8000-000000000009', name: 'Old', muscleGroup: 'CORE' } }])); }");
        page.reload();
        assertThat(page.getByLabel("Email")).isVisible();
        signIn();
        tab("Exercises").click();
        assertThat(page.getByText("No exercises yet")).isVisible();
        Assertions.assertThat(count("select count(*) from exercise")).isZero();
    }

    @Test
    void alertsThatNoLongerExistShowEnableAgain() {
        page.navigate("/");
        // The phone had alerts on, but the server no longer has its subscription (the upgrade emptied the table).
        page.evaluate("() => localStorage.setItem('gt.prefs.v1', JSON.stringify({ alertsEnabled: true }))");
        signIn();
        tab("Workout").click();
        page.getByLabel("Settings").click();
        assertThat(button("Enable")).isVisible();
    }

    @Test
    void alertsOfThisPhoneMoveToTheSignedInAccount() {
        page.addInitScript("""
                window.PushManager = window.PushManager || function () {};
                window.Notification = window.Notification || function () {};
                const subscription = { endpoint: 'https://push.example/this-phone',
                  toJSON: () => ({ endpoint: 'https://push.example/this-phone', keys: { p256dh: 'key', auth: 'auth' } }) };
                navigator.serviceWorker.getRegistration = async () => ({ pushManager: { getSubscription: async () => subscription } });
                """);
        page.navigate("/");
        page.evaluate("() => localStorage.setItem('gt.prefs.v1', JSON.stringify({ alertsEnabled: true }))");
        signIn();
        page.waitForCondition(() -> count("select count(*) from push_subscription") == 1);
        Assertions.assertThat(jdbc.queryForObject("select user_id from push_subscription", java.util.UUID.class))
                .isEqualTo(testerId);
    }
}
