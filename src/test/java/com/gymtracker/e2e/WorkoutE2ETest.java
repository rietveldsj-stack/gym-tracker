package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Clock;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.math.BigDecimal;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class WorkoutE2ETest extends E2ETestBase {

    private void startSessionWithSquat() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        assertThat(page.locator("#session-timer")).isVisible();
    }

    private void addExerciseAndLogSet(String exercise, String weight, String reps, boolean warmup) {
        button("+ Add exercise").click();
        button(exercise).click();
        fillAndSaveSet(weight, reps, warmup);
    }

    private void fillAndSaveSet(String weight, String reps, boolean warmup) {
        page.getByLabel("Weight in kg").fill(weight);
        page.getByLabel("Reps", new Page.GetByLabelOptions().setExact(true)).fill(reps);
        if (warmup) {
            radio("Warmup").click();
        }
        button("Save").click();
    }

    private Locator radio(String name) {
        return page.getByRole(AriaRole.RADIO, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private Locator setRows() {
        return page.locator(".set-row");
    }

    @Test
    void fullWorkoutShowsLiveTimerAndDuration() {
        page.clock().install(new Clock.InstallOptions().setTime("2026-10-05T07:59:00Z"));
        page.clock().pauseAt("2026-10-05T08:00:00Z");
        startSessionWithSquat();
        assertThat(page.locator("#session-timer")).hasText("0:00:00");

        addExerciseAndLogSet("Squat", "40", "10", true);
        assertThat(setRows().nth(0)).containsText("40 kg × 10");
        assertThat(setRows().nth(0)).containsText("Warmup");

        button("+ Set").click();
        assertThat(page.getByLabel("Weight in kg")).hasValue("40");
        assertThat(page.getByLabel("Reps", new Page.GetByLabelOptions().setExact(true))).hasValue("10");
        assertThat(radio("Work")).hasAttribute("aria-checked", "true");
        page.getByLabel("Increase weight").click();
        button("Save").click();
        assertThat(setRows().nth(1)).containsText("42.5 kg × 10");
        assertThat(setRows().nth(1)).not().containsText("Warmup");

        page.clock().runFor("42:00");
        assertThat(page.locator("#session-timer")).hasText("0:42:00");
        button("End").click();
        button("End workout").click();
        assertThat(page.locator("#summary-duration")).hasText("42 min");
        assertThat(page.locator("[data-stat='exercises'] strong")).hasText("1");
        assertThat(page.locator("[data-stat='work-sets'] strong")).hasText("1");
        button("Done").click();
        assertThat(button("Start session")).isVisible();

        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session where ended_at is not null")).isEqualTo(1);
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(2);
    }

    @Test
    void editsAndDeletesSets() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "40", "10", false);
        setRows().nth(0).click();
        page.getByLabel("Reps", new Page.GetByLabelOptions().setExact(true)).fill("12");
        button("Save").click();
        assertThat(setRows().nth(0)).containsText("40 kg × 12");

        setRows().nth(0).click();
        button("Delete set").click();
        button("Delete").click();
        assertThat(setRows()).hasCount(0);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_set")).isZero();
    }

    @Test
    void typedCommaWeightIsLoggedAsDecimal() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "42,5", "8", false);
        assertThat(setRows().nth(0)).containsText("42.5 kg × 8");
        waitUntilSynced();
        Assertions.assertThat(jdbc.queryForObject("select weight_kg from workout_set", BigDecimal.class))
                .isEqualByComparingTo("42.5");
    }

    @Test
    void doubleTapOnSaveLogsOneSet() {
        startSessionWithSquat();
        button("+ Add exercise").click();
        button("Squat").click();
        page.getByLabel("Weight in kg").fill("40");
        button("Save").dblclick();
        assertThat(setRows()).hasCount(1);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(1);
    }

    @Test
    void setsLoggedOfflineSyncWhenBackOnline() {
        startSessionWithSquat();
        waitUntilSynced();
        context.setOffline(true);
        addExerciseAndLogSet("Squat", "40", "10", false);
        button("+ Set").click();
        button("Save").click();
        button("+ Set").click();
        button("Save").click();
        assertThat(setRows()).hasCount(3);
        assertThat(page.locator("#syncbar")).hasText("3 changes waiting to sync");
        Assertions.assertThat(count("select count(*) from workout_set")).isZero();

        context.setOffline(false);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(3);
    }

    @Test
    void resumesAfterReloadAndShowsLastTimeNextSession() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "40", "10", false);
        waitUntilSynced();
        page.reload();
        assertThat(setRows()).hasCount(1);
        assertThat(page.locator("#session-timer")).isVisible();

        button("End").click();
        button("End workout").click();
        button("Done").click();
        waitUntilSynced();

        button("Start session").click();
        button("+ Add exercise").click();
        button("Squat").click();
        assertThat(page.getByText("Last time: 40 kg × 10")).isVisible();
        assertThat(page.getByLabel("Weight in kg")).hasValue("40");
    }

    @Test
    void endingEmptySessionDiscardsIt() {
        startSessionWithSquat();
        button("End").click();
        assertThat(page.getByText("No sets logged. This workout won't be saved.")).isVisible();
        button("End workout").click();
        assertThat(page.locator("#toast")).hasText("Empty workout discarded");
        assertThat(button("Start session")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session")).isZero();
    }

    @Test
    void discardsSessionFromMenu() {
        startSessionWithSquat();
        addExerciseAndLogSet("Squat", "40", "10", false);
        page.getByLabel("More options").click();
        button("Discard session").click();
        button("Discard").click();
        assertThat(button("Start session")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session")).isZero();
        Assertions.assertThat(count("select count(*) from workout_set")).isZero();
    }

    @Test
    void newExerciseFromPickerGoesStraightToSetSheet() {
        signIn();
        tab("Workout").click();
        button("Start session").click();
        button("+ Add exercise").click();
        button("+ New exercise").click();
        page.getByLabel("Name").fill("Hip thrust");
        button("Glutes").click();
        button("Save").click();
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Hip thrust"))).isVisible();
        fillAndSaveSet("60", "10", false);
        assertThat(setRows().nth(0)).containsText("60 kg × 10");
    }

    @Test
    void pickerShowsExercisesThatArriveWhileItIsOpen() {
        seedExercise("Squat", "QUADS");
        page.navigate("/");
        page.getByLabel("Email").fill(com.gymtracker.TestUsers.EMAIL);
        page.getByLabel("Password", new com.microsoft.playwright.Page.GetByLabelOptions().setExact(true))
                .fill(com.gymtracker.TestUsers.PASSWORD);
        // Hold back the first data load until the picker is open, like a slow first launch.
        page.route("**/api/exercises", route -> {
            page.waitForCondition(() -> page.locator(".picker-list").count() > 0);
            route.resume();
        });
        button("Sign in").click();
        tab("Workout").click();
        button("Start session").click();
        button("+ Add exercise").click();
        assertThat(button("Squat")).isVisible();
    }
}
