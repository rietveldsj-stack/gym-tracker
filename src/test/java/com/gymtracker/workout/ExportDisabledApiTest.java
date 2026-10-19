package com.gymtracker.workout;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gymtracker.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.export.key=")
class ExportDisabledApiTest extends IntegrationTestBase {

    @Test
    void exportDisabledWhenKeyUnset() throws Exception {
        mvc.perform(get("/api/export/sessions").param("from", "2026-10-18T22:00:00Z").param("to", "2026-10-25T23:00:00Z")
                        .header("Authorization", "Bearer "))
                .andExpect(status().isNotFound());
    }
}
