package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordsE2ETest extends E2ETestBase {

    private void logSet(String weight, String reps) {
        page.getByLabel("Weight in kg").fill(weight);
        page.getByLabel("Reps", new Page.GetByLabelOptions().setExact(true)).fill(reps);
        button("Save").click();
    }

    @Test
    void personalRecordsAreCelebratedAndListedInSummary() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID earlier = seedEndedSession("2026-09-28", "2026-09-28T08:00:00Z", "2026-09-28T09:00:00Z");
        seedSet(earlier, squat, "40", 10, "WORK", "2026-09-28T08:10:00Z");
        signIn();
        tab("Workout").click();
        button("Start session").click();

        button("+ Add exercise").click();
        button("Squat").click();
        logSet("40", "12");
        assertThat(page.locator("#toast")).hasText("🏆 New PR!");
        assertThat(page.locator(".set-row").nth(0).locator(".pr")).hasCount(1);

        button("+ Set").click();
        logSet("30", "5");
        assertThat(page.locator(".set-row").nth(1).locator(".pr")).hasCount(0);

        button("+ Set").click();
        logSet("45", "3");
        assertThat(page.locator(".pr")).hasCount(2);

        button("End").click();
        button("End workout").click();
        assertThat(page.locator(".pr-list li")).hasCount(2);
        assertThat(page.locator(".pr-list li").nth(0)).hasText("Squat — 40 kg × 12 · rep record");
        assertThat(page.locator(".pr-list li").nth(1)).hasText("Squat — 45 kg × 3 · heaviest weight");
    }

    @Test
    void firstEverSetAndWarmupsAreNoRecords() {
        seedExercise("Squat", "QUADS");
        signIn();
        tab("Workout").click();
        button("Start session").click();
        button("+ Add exercise").click();
        button("Squat").click();
        logSet("40", "10");
        assertThat(page.locator(".pr")).hasCount(0);
        assertThat(page.locator("#toast")).not().hasText("🏆 New PR!");
    }
}
