package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.AriaRole;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class HomeAndCalendarE2ETest extends E2ETestBase {

    private Locator heading() {
        return page.getByRole(AriaRole.HEADING, new com.microsoft.playwright.Page.GetByRoleOptions().setLevel(1));
    }

    private Locator day(String date) {
        return page.locator("[data-day='" + date + "']");
    }

    private void setName(String name) {
        jdbc.update("update app_user set name = ? where id = ?", name, testerId);
    }

    /** Amsterdam is UTC+2 in October. */
    private void phoneTimeIs(String utc) {
        page.clock().setFixedTime(utc);
    }

    private UUID seedWorkout(String date, String startUtc, String endUtc) {
        UUID squat = jdbc.queryForList("select id from exercise where name = 'Squat'", UUID.class).stream().findFirst()
                .orElseGet(() -> seedExercise("Squat", "QUADS"));
        UUID session = seedEndedSession(date, date + "T" + startUtc + "Z", date + "T" + endUtc + "Z");
        seedSet(session, squat, "60", 5, "WORK", date + "T" + startUtc + "Z");
        return session;
    }

    @Test
    void greetsByNameForTheTimeOfDay() {
        setName("Janet");
        phoneTimeIs("2026-10-07T07:30:00Z"); // 09:30
        signIn();
        assertThat(heading()).hasText("Good morning, Janet");
        assertThat(page.getByText("Ready for your workout?")).isVisible();
        assertThat(button("Start session")).isVisible();
        assertThat(page.getByText("Last workout")).hasCount(0);

        phoneTimeIs("2026-10-07T17:00:00Z"); // 19:00
        page.reload();
        assertThat(heading()).hasText("Good evening, Janet");
    }

    @Test
    void greetsWithoutANameWhenThereIsNone() {
        phoneTimeIs("2026-10-07T12:30:00Z"); // 14:30
        signIn();
        assertThat(heading()).hasText("Good afternoon");
    }

    @Test
    void nameCanBeChangedInSettings() {
        setName("Janet");
        signIn();
        page.getByLabel("Settings").click();
        Locator name = page.getByLabel("Name", new com.microsoft.playwright.Page.GetByLabelOptions().setExact(true));
        assertThat(name).hasValue("Janet");
        name.fill("Jan");
        name.press("Enter");
        button("Done").click();
        assertThat(heading()).containsText(", Jan");
        waitUntilSynced();
        Assertions.assertThat(jdbc.queryForObject("select name from app_user where id = ?", String.class, testerId))
                .isEqualTo("Jan");
    }

    @Test
    void blankNameIsNotSaved() {
        setName("Janet");
        signIn();
        page.getByLabel("Settings").click();
        Locator name = page.getByLabel("Name", new com.microsoft.playwright.Page.GetByLabelOptions().setExact(true));
        name.fill("   ");
        name.press("Enter");
        assertThat(page.getByText("Enter your name")).isVisible();
        button("Done").click();
        assertThat(heading()).containsText(", Janet");
    }

    @Test
    void calendarHighlightsWorkoutDaysAndOpensThem() {
        phoneTimeIs("2026-10-07T10:00:00Z");
        seedWorkout("2026-10-01", "08:00:00", "08:40:00");
        seedWorkout("2026-10-05", "17:00:00", "18:00:00");
        seedWorkout("2026-09-14", "08:00:00", "09:00:00");
        signIn();
        tab("Stats").click();

        assertThat(page.locator("#month-title")).hasText("October 2026");
        assertThat(page.locator("button[data-day]")).hasCount(2);
        assertThat(day("2026-10-07")).hasCount(0);
        assertThat(page.locator(".cal-day.today")).hasText("7");
        assertThat(page.locator("#weekly-chart")).hasCount(0); // workouts per week was replaced by the calendar
        assertThat(button("Next month")).isDisabled();

        day("2026-10-05").click();
        assertThat(heading()).hasText("Monday 5 October");
        assertThat(page.locator("#session-detail .card")).containsText("60 kg × 5");
        assertThat(tab("Stats")).hasAttribute("aria-current", "page");

        button("‹ Stats").click();
        assertThat(page.locator("#month-title")).hasText("October 2026");
        button("Previous month").click();
        assertThat(page.locator("#month-title")).hasText("September 2026");
        assertThat(page.locator("button[data-day]")).hasCount(1);
        assertThat(button("Previous month")).isDisabled(); // nothing before the first workout
        day("2026-09-14").click();
        assertThat(heading()).hasText("Monday 14 September");
        button("‹ Stats").click();
        assertThat(page.locator("#month-title")).hasText("September 2026"); // stays on the month she was looking at
    }

    @Test
    void dayWithTwoWorkoutsLetsHerPickOne() {
        phoneTimeIs("2026-10-07T10:00:00Z");
        seedWorkout("2026-10-05", "06:00:00", "06:30:00");
        seedWorkout("2026-10-05", "17:00:00", "18:00:00");
        signIn();
        tab("Stats").click();
        day("2026-10-05").click();
        Locator choices = page.locator(".sheet [data-session]");
        assertThat(choices).hasCount(2);
        assertThat(choices.nth(0)).containsText("08:00"); // in Amsterdam time, earliest first
        assertThat(choices.nth(1)).containsText("19:00");
        assertThat(choices.nth(1)).containsText("1 h 00 min");
        choices.nth(1).click();
        assertThat(heading()).hasText("Monday 5 October");
        assertThat(page.locator(".sheet")).hasCount(0);
    }

    @Test
    void calendarWorksOffline() {
        phoneTimeIs("2026-10-07T10:00:00Z");
        seedWorkout("2026-10-05", "08:00:00", "09:00:00");
        signIn();
        context.setOffline(true);
        tab("Stats").click();
        assertThat(day("2026-10-05")).isVisible();
        assertThat(page.getByText("Muscle-group stats appear once the app has been online")).hasCount(0);
    }
}
