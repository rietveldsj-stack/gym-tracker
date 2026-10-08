package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EditWorkoutE2ETest extends E2ETestBase {

    private UUID squat;
    private UUID workout;

    @BeforeEach
    void finishedWorkout() {
        page.clock().setFixedTime("2026-10-07T10:00:00Z");
        squat = seedExercise("Squat", "QUADS");
        seedExercise("Bench press", "CHEST");
        workout = seedEndedSession("2026-10-05", "2026-10-05T08:00:00Z", "2026-10-05T09:00:00Z");
    }

    private Locator sheetButton(String name) {
        return page.locator(".sheet").getByRole(AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName(name).setExact(true));
    }

    private Locator reps() {
        return page.getByLabel("Reps", new com.microsoft.playwright.Page.GetByLabelOptions().setExact(true));
    }

    private Locator setButtons() {
        return page.locator("#session-detail [data-set]");
    }

    private void openWorkoutFromHistory() {
        tab("History").click();
        page.locator("[data-session]").first().click();
        assertThat(page.locator("#session-detail .card")).not().hasCount(0);
    }

    private String weightOf(String sql) {
        return jdbc.queryForObject(sql, java.math.BigDecimal.class, workout).stripTrailingZeros().toPlainString();
    }

    @Test
    void setsOfAFinishedWorkoutCanBeChangedAndAdded() {
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:20:00Z");
        signIn();
        openWorkoutFromHistory();
        assertThat(setButtons()).hasCount(0); // read-only until she taps Edit

        button("Edit").click();
        setButtons().first().click();
        assertThat(page.getByText("Top set last time")).hasCount(0); // that's about her latest workout, not this one
        page.getByLabel("Weight in kg").fill("62.5");
        sheetButton("Save").click();
        assertThat(page.locator("#session-detail .card")).containsText("62.5 kg × 5");

        button("+ Set").click();
        assertThat(page.getByLabel("Weight in kg")).hasValue("60"); // from this workout's last set
        reps().fill("3");
        sheetButton("Save").click();
        assertThat(setButtons()).hasCount(3);
        assertThat(setButtons().nth(2)).containsText("60 kg × 3");
        assertThat(page.getByText("1 h 00 min · 3 sets")).isVisible();
        assertThat(page.locator("#toast")).not().containsText("PR"); // no cheer or rest timer for an old workout
        assertThat(page.locator("#restbar")).isHidden();

        button("Done").click();
        assertThat(setButtons()).hasCount(0);
        waitUntilSynced();
        Assertions.assertThat(weightOf("select weight_kg from workout_set where session_id = ? order by logged_at limit 1"))
                .isEqualTo("62.5");
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(3);
        Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from workout_set where session_id = ? and logged_at > '2026-10-05T09:00:00Z'",
                Integer.class, workout)).as("added set stays inside the workout's time").isZero();

        button("‹ History").click();
        assertThat(page.locator("[data-session]").first()).containsText("3 sets");
    }

    @Test
    void aForgottenExerciseCanBeAdded() {
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        signIn();
        openWorkoutFromHistory();
        button("Edit").click();
        button("+ Add exercise").click();
        button("Bench press").click();
        page.getByLabel("Weight in kg").fill("40");
        reps().fill("8");
        sheetButton("Save").click();
        assertThat(page.locator("#session-detail .card h2")).hasText(new String[] {"Squat", "Bench press"});
        waitUntilSynced();
        button("‹ History").click();
        assertThat(page.locator("[data-session]").first()).containsText("Quads · Chest");
    }

    @Test
    void aWorkoutCanBeDeletedFromTheCalendar() {
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        signIn();
        tab("Stats").click();
        page.locator("[data-day='2026-10-05']").click();
        button("Edit").click();
        button("Delete workout").click();
        assertThat(page.getByText("Delete this workout? All its sets will be deleted.")).isVisible();
        sheetButton("Delete workout").click();
        assertThat(page.locator("#toast")).hasText("Workout deleted");
        assertThat(page.locator("#month-title")).hasText("October 2026");
        assertThat(page.locator("[data-day='2026-10-05']")).hasCount(0);
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session")).isZero();
        tab("History").click();
        assertThat(page.getByText("No workouts yet.")).isVisible();
    }

    @Test
    void deletingTheOnlySetDeletesTheWorkout() {
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        signIn();
        openWorkoutFromHistory();
        button("Edit").click();
        setButtons().first().click();
        sheetButton("Delete set").click();
        assertThat(page.getByText("This is the workout's only set. Delete the whole workout?")).isVisible();
        sheetButton("Delete workout").click();
        assertThat(page.getByText("No workouts yet.")).isVisible();
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_session")).isZero();
    }

    @Test
    void editsMadeOfflineSyncLaterAndStay() {
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:20:00Z");
        signIn();
        openWorkoutFromHistory();
        context.setOffline(true);
        button("Edit").click();
        setButtons().nth(1).click();
        sheetButton("Delete set").click();
        sheetButton("Delete").click();
        assertThat(setButtons()).hasCount(1);
        assertThat(page.locator("#syncbar")).hasText("1 change waiting to sync");

        context.setOffline(false);
        page.evaluate("window.dispatchEvent(new Event('online'))");
        waitUntilSynced();
        Assertions.assertThat(count("select count(*) from workout_set")).isEqualTo(1);
        assertThat(setButtons()).hasCount(1); // still gone once the server's data has replaced the pending change
        page.reload();
        openWorkoutFromHistory();
        assertThat(page.locator("#session-detail .set-row")).hasCount(1);
    }

    @Test
    void setSheetShowsTheTopSetOfTheLastWorkout() {
        seedSet(workout, squat, "40", 10, "WARMUP", "2026-10-05T08:05:00Z");
        seedSet(workout, squat, "60", 5, "WORK", "2026-10-05T08:10:00Z");
        seedSet(workout, squat, "60", 6, "WORK", "2026-10-05T08:20:00Z");
        seedSet(workout, squat, "50", 8, "WORK", "2026-10-05T08:30:00Z");
        signIn();
        button("Start session").click();
        button("+ Add exercise").click();
        button("Squat").click();
        assertThat(page.getByText("Top set last time: 60 kg × 6")).isVisible();
        assertThat(page.getByLabel("Weight in kg")).hasValue("60");
        assertThat(reps()).hasValue("6");
    }
}
