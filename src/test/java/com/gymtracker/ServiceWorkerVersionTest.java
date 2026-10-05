package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Each build must get its own cache name, so a deploy installs a complete new cache instead of mixing files. */
class ServiceWorkerVersionTest {

    @Test
    void builtServiceWorkerHasPerBuildCacheName() throws IOException {
        String serviceWorker = new ClassPathResource("static/sw.js").getContentAsString(StandardCharsets.UTF_8);
        assertThat(serviceWorker).containsPattern("const CACHE = 'gym-tracker-\\d{14}';");
    }

    @Test
    void builtServiceWorkerNeverWritesRevalidatedFilesIntoTheLiveCache() throws IOException {
        String serviceWorker = new ClassPathResource("static/sw.js").getContentAsString(StandardCharsets.UTF_8);
        assertThat(serviceWorker).doesNotContain("cache.put(");
        assertThat(serviceWorker).contains("cache: 'reload'");
    }
}
