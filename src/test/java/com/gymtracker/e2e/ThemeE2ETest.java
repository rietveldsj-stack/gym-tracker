package com.gymtracker.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ThemeE2ETest extends E2ETestBase {

    private static final String LOW_CONTRAST = """
            (theme) => {
              document.documentElement.dataset.theme = theme;
              const css = getComputedStyle(document.documentElement);
              const hex = (name) => css.getPropertyValue(name).trim();
              const lum = (h) => {
                const [r, g, b] = [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255)
                  .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
                return 0.2126 * r + 0.7152 * g + 0.0722 * b;
              };
              const ratio = (a, b) => {
                const [hi, lo] = [lum(hex(a)), lum(hex(b))].sort((x, y) => y - x);
                return (hi + 0.05) / (lo + 0.05);
              };
              const low = [];
              for (const text of ['--text-primary', '--text-secondary', '--text-muted', '--danger'])
                for (const surface of ['--surface-0', '--surface-1', '--surface-2'])
                  if (!(ratio(text, surface) >= 4.5)) low.push(`${text} on ${surface}`);
              if (!(ratio('--accent-text', '--accent') >= 4.5)) low.push('--accent-text on --accent');
              if (!(ratio('--series-1', '--surface-1') >= 3)) low.push('--series-1 on --surface-1');
              return low;
            }""";

    private String accent() {
        return (String) page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()");
    }

    private void pickTheme(String name) {
        tab("Workout").click();
        page.getByLabel("Settings").click();
        page.getByRole(AriaRole.RADIO, new Page.GetByRoleOptions().setName(name).setExact(true)).click();
        button("Done").click();
    }

    @Test
    void pickedThemeAppliesAtOnceAndAfterAReload() {
        signIn();
        pickTheme("Pink");
        assertThat(page.locator("html")).hasAttribute("data-theme", "pink");
        Assertions.assertThat(accent()).isEqualTo("#c2185b");

        page.reload();
        assertThat(page.locator("html")).hasAttribute("data-theme", "pink");
        Assertions.assertThat(page.evaluate("() => document.querySelector('meta[name=\"theme-color\"]').content"))
                .isEqualTo("#fff8fa");

        pickTheme("Red & black");
        Assertions.assertThat(accent()).isEqualTo("#d61f2c");
        pickTheme("Blue");
        assertThat(page.locator("html")).not().hasAttribute("data-theme", "redblack");
        Assertions.assertThat(page.locator("html").getAttribute("data-theme")).isNull();
    }

    private Object touchIcon() {
        return page.evaluate("() => document.querySelector('link[rel=\"apple-touch-icon\"]').getAttribute('href')");
    }

    @Test
    void homeScreenIconFollowsTheTheme() {
        signIn();
        Assertions.assertThat(touchIcon()).isEqualTo("/icons/apple-touch-icon.png");
        pickTheme("Pink");
        Assertions.assertThat(touchIcon()).isEqualTo("/icons/apple-touch-icon-pink.png");
        pickTheme("Red & black");
        Assertions.assertThat(touchIcon()).isEqualTo("/icons/apple-touch-icon-redblack.png");
        Assertions.assertThat(page.request().get("/icons/apple-touch-icon-redblack.png").status()).isEqualTo(200);
        page.reload();
        assertThat(page.locator("#tabs")).isVisible();
        Assertions.assertThat(touchIcon()).isEqualTo("/icons/apple-touch-icon-redblack.png");
        pickTheme("Blue");
        Assertions.assertThat(touchIcon()).isEqualTo("/icons/apple-touch-icon.png");
    }

    @Test
    void savedThemeIsUsedBeforeTheAppStarts() {
        page.navigate("/");
        page.evaluate("() => localStorage.setItem('gt.prefs.v1', JSON.stringify({ theme: 'redblack' }))");
        // readyState turns 'interactive' when the HTML is parsed and before module scripts (app.js) run, so this
        // sees only what the inline script in index.html did. (Blocking app.js with page.route wouldn't work: the
        // service worker serves it from its cache, and Playwright doesn't intercept those requests.)
        page.addInitScript("document.addEventListener('readystatechange', () => { "
                + "if (document.readyState === 'interactive') { window.__themeAtParse = document.documentElement.dataset.theme ?? null; "
                + "window.__iconAtParse = document.querySelector('link[rel=\"apple-touch-icon\"]').getAttribute('href'); } });");
        page.reload();
        Assertions.assertThat(page.evaluate("() => window.__themeAtParse")).isEqualTo("redblack");
        Assertions.assertThat(page.evaluate("() => window.__iconAtParse")).isEqualTo("/icons/apple-touch-icon-redblack.png");
        assertThat(page.locator("img.app-icon")).hasAttribute("src", "/icons/apple-touch-icon-redblack.png"); // sign-in screen
    }

    @Test
    void newThemesAreReadable() {
        page.navigate("/");
        for (String theme : List.of("pink", "redblack")) {
            @SuppressWarnings("unchecked")
            List<String> low = (List<String>) page.evaluate(LOW_CONTRAST, theme);
            Assertions.assertThat(low).as(theme).isEmpty();
        }
    }

    @Test
    void chartsUseTheThemeColours() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Amsterdam"));
        java.util.UUID squat = seedExercise("Squat", "QUADS");
        java.util.UUID session = seedEndedSession(today.toString(), today + "T08:00:00Z", today + "T09:00:00Z");
        seedSet(session, squat, "60", 5, "WORK", today + "T08:10:00Z");
        signIn();
        pickTheme("Red & black");
        tab("Stats").click();
        page.waitForFunction("() => window.Chart && document.querySelector('canvas') && window.Chart.getChart(document.querySelector('canvas'))");
        Object colour = page.evaluate("() => window.Chart.getChart(document.querySelector('canvas')).data.datasets[0].backgroundColor");
        Assertions.assertThat(colour).isEqualTo("#ef4655");
    }
}
