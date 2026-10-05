package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Clock;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

class RestTimerE2ETest extends E2ETestBase {

    private void freezeClock() {
        page.clock().install(new Clock.InstallOptions().setTime("2026-10-05T07:59:00Z"));
        page.clock().pauseAt("2026-10-05T08:00:00Z");
    }

    private void startSessionAndLogSet() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        logSquatSet();
    }

    private void logSquatSet() {
        button("+ Add exercise").click();
        button("Squat").click();
        page.getByLabel("Weight in kg").fill("40");
        button("Save").click();
    }

    private Locator restTime() {
        return page.locator("#rest-time");
    }

    @Test
    void restStartsAfterSavingASetAndCountsDown() {
        freezeClock();
        startSessionAndLogSet();
        assertThat(restTime()).hasText("1:30");
        button("+15 s").click();
        assertThat(restTime()).hasText("1:45");
        button("−15 s").click();
        assertThat(restTime()).hasText("1:30");

        page.clock().runFor("01:30");
        assertThat(page.locator("#restbar")).containsText("Rest over");
        page.clock().runFor("00:06");
        assertThat(page.locator("#restbar")).isHidden();
    }

    @Test
    void skipHidesTheTimer() {
        freezeClock();
        startSessionAndLogSet();
        assertThat(restTime()).isVisible();
        button("Skip").click();
        assertThat(page.locator("#restbar")).isHidden();
    }

    @Test
    void endingTheWorkoutStopsTheRest() {
        freezeClock();
        startSessionAndLogSet();
        assertThat(restTime()).isVisible();
        button("End").click();
        button("End workout").click();
        assertThat(page.locator("#restbar")).isHidden();
    }

    @Test
    void restTimeAndAutoStartComeFromSettings() {
        freezeClock();
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        page.getByLabel("Settings").click();
        page.getByLabel("Shorter rest").click();
        page.getByLabel("Shorter rest").click();
        assertThat(page.locator("#rest-seconds")).hasText("1:00");
        page.getByLabel("Auto-start rest after each set").uncheck();
        button("Done").click();

        button("Start session").click();
        logSquatSet();
        assertThat(button("Start rest")).isVisible();
        button("Start rest").click();
        assertThat(restTime()).hasText("1:00");
    }

    @Test
    void restSurvivesReload() {
        startSessionAndLogSet();
        assertThat(restTime()).isVisible();
        page.reload();
        assertThat(restTime()).isVisible();
    }

    @Test
    void lockScreenAlertsNeedTheHomeScreenApp() {
        signIn();
        tab("Workout").click();
        page.getByLabel("Settings").click();
        button("Enable").click();
        assertThat(page.getByText("Add the app to your Home Screen first")).isVisible();
    }
}
