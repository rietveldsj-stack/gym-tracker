package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every static file must be precached by the service worker, or the app opens broken offline. */
class ServiceWorkerAssetsTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void everyStaticFileIsListedInServiceWorker() throws IOException {
        String serviceWorker = Files.readString(STATIC.resolve("sw.js"));
        try (Stream<Path> files = Files.walk(STATIC)) {
            List<String> missing = files.filter(Files::isRegularFile)
                    .map(path -> "/" + STATIC.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> !path.equals("/sw.js") && !path.endsWith(".DS_Store"))
                    .filter(path -> !serviceWorker.contains("'" + path + "'"))
                    .toList();
            assertThat(missing).isEmpty();
        }
    }
}
