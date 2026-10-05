package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ChartsE2ETest extends E2ETestBase {

    private final LocalDate today = LocalDate.now(ZoneId.of("Europe/Amsterdam"));

    private UUID seedSession(LocalDate date) {
        return seedEndedSession(date.toString(), date + "T08:00:00Z", date + "T09:00:00Z");
    }

    private void waitForChart(String canvasId) {
        page.waitForFunction("id => !!(window.Chart && window.Chart.getChart(document.getElementById(id)))", canvasId);
    }

    @SuppressWarnings("unchecked")
    private List<Double> chartValues(String canvasId) {
        List<Number> values = (List<Number>) page.evaluate(
                "id => window.Chart.getChart(document.getElementById(id)).data.datasets[0].data", canvasId);
        return values.stream().map(Number::doubleValue).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> chartLabels(String canvasId) {
        return (List<String>) page.evaluate("id => window.Chart.getChart(document.getElementById(id)).data.labels", canvasId);
    }

    private Locator radio(String name) {
        return page.getByRole(AriaRole.RADIO, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    @Test
    void exerciseDetailShowsRecordsAndProgressChart() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID first = seedSession(today.minusDays(7));
        seedSet(first, squat, "55", 5, "WORK", today.minusDays(7) + "T08:10:00Z");
        UUID second = seedSession(today);
        seedSet(second, squat, "60", 5, "WORK", today + "T08:10:00Z");
        seedSet(second, squat, "50", 10, "WORK", today + "T08:20:00Z");
        signIn();
        tab("Exercises").click();
        button("Squat").click();

        assertThat(page.locator("#heaviest")).hasText("60 kg × 5");
        assertThat(page.locator(".records tbody tr")).hasCount(3);
        waitForChart("progress-chart");
        Assertions.assertThat(chartValues("progress-chart")).containsExactly(55.0, 60.0);

        radio("Estimated 1RM").click();
        waitForChart("progress-chart");
        Assertions.assertThat(chartValues("progress-chart")).containsExactly(64.0, 70.0);
    }

    @Test
    void bodyweightOnlyExerciseShowsRepRecordsButNoChart() {
        UUID pullUp = seedExercise("Pull-up", "BACK");
        UUID session = seedSession(today);
        seedSet(session, pullUp, "0", 8, "WORK", today + "T08:10:00Z");
        signIn();
        tab("Exercises").click();
        button("Pull-up").click();
        assertThat(page.getByText("Only bodyweight sets so far")).isVisible();
        assertThat(page.locator(".records tbody tr td").nth(0)).hasText("Bodyweight");
        assertThat(page.locator(".records tbody tr td").nth(1)).hasText("8");
        assertThat(page.locator("#progress-chart")).hasCount(0);
    }

    @Test
    void statsTabShowsWeeklyCharts() {
        UUID squat = seedExercise("Squat", "QUADS");
        UUID session = seedSession(today);
        seedSet(session, squat, "20", 10, "WARMUP", today + "T08:05:00Z");
        seedSet(session, squat, "60", 5, "WORK", today + "T08:10:00Z");
        seedSet(session, squat, "60", 5, "WORK", today + "T08:15:00Z");
        signIn();
        tab("Stats").click();

        waitForChart("weekly-chart");
        List<Double> perWeek = chartValues("weekly-chart");
        Assertions.assertThat(perWeek).hasSize(12);
        Assertions.assertThat(perWeek.getLast()).isEqualTo(1.0);

        assertThat(page.locator("#week-title")).hasText("This week");
        waitForChart("muscle-chart");
        Assertions.assertThat(chartLabels("muscle-chart")).containsExactly("Quads");
        Assertions.assertThat(chartValues("muscle-chart")).containsExactly(2.0);

        page.getByLabel("Previous week").click();
        assertThat(page.getByText("No workouts this week")).isVisible();
    }
}
