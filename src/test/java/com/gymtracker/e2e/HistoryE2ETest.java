package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryE2ETest extends E2ETestBase {

    private void seedWorkout() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID bench = seedExercise("Bench press", "CHEST");
        UUID session = seedEndedSession("2026-10-01", "2026-10-01T08:00:00Z", "2026-10-01T08:42:00Z");
        seedSet(session, squat, "20", 10, "WARMUP", "2026-10-01T08:05:00Z");
        seedSet(session, squat, "60", 5, "WORK", "2026-10-01T08:10:00Z");
        seedSet(session, bench, "40", 8, "WORK", "2026-10-01T08:20:00Z");
    }

    @Test
    void listsWorkoutsAndShowsTheirSets() {
        seedWorkout();
        signIn();
        tab("History").click();
        Locator row = page.locator("[data-session]");
        assertThat(row).containsText("1 Oct");
        assertThat(row).containsText("42 min · 3 sets · Quads · Chest");

        row.click();
        assertThat(page.locator("h1")).containsText("1 October");
        Locator cards = page.locator("#session-detail .card");
        assertThat(cards).hasCount(2);
        assertThat(cards.nth(0)).containsText("Squat");
        assertThat(cards.nth(0).locator(".set-row").nth(0)).containsText("20 kg × 10");
        assertThat(cards.nth(0).locator(".set-row").nth(0)).containsText("Warmup");
        assertThat(cards.nth(1)).containsText("40 kg × 8");
    }

    @Test
    void detailStaysAvailableOfflineOnceLoaded() {
        seedWorkout();
        signIn();
        tab("History").click();
        page.locator("[data-session]").click();
        assertThat(page.locator("#session-detail .card")).hasCount(2);
        button("‹ History").click();
        context.setOffline(true);
        page.locator("[data-session]").click();
        assertThat(page.locator("#session-detail .card")).hasCount(2);
    }

    @Test
    void justFinishedWorkoutShowsUpRightAway() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        button("+ Add exercise").click();
        button("Squat").click();
        page.getByLabel("Weight in kg").fill("40");
        button("Save").click();
        button("End").click();
        button("End workout").click();
        button("Done").click();

        tab("History").click();
        assertThat(page.locator("[data-session]")).containsText("1 set · Quads");
        page.locator("[data-session]").click();
        assertThat(page.locator("#session-detail .set-row")).containsText("40 kg × 10");
    }
}
