package com.gymtracker.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsUnitE2ETest extends E2ETestBase {

    @Test
    @SuppressWarnings("unchecked")
    void browserUnitTestsPass() {
        page.navigate("/test/unit.html");
        page.waitForFunction("() => window.__results !== undefined");
        List<Map<String, Object>> results = (List<Map<String, Object>>) page.evaluate("() => window.__results");
        List<String> failures = results.stream()
                .filter(result -> !Boolean.TRUE.equals(result.get("ok")))
                .map(result -> result.get("name") + ": " + result.get("error"))
                .toList();
        assertThat(results).isNotEmpty();
        assertThat(failures).isEmpty();
    }
}
