package com.gymtracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Each theme has its own home-screen icon: a dumbbell in the theme's colours. */
class AppIconsTest {

    @ParameterizedTest
    @CsvSource({
        "apple-touch-icon.png, 2a78d6, ffffff",
        "apple-touch-icon-pink.png, c2185b, ffffff",
        "apple-touch-icon-redblack.png, d61f2c, 0b0b0c",
    })
    void iconHasTheThemeColours(String file, String background, String dumbbell) throws IOException {
        BufferedImage icon = ImageIO.read(Path.of("src/main/resources/static/icons", file).toFile());
        assertThat(icon.getWidth()).isEqualTo(180);
        assertThat(icon.getHeight()).isEqualTo(180);
        assertThat(hex(icon.getRGB(0, 0))).as("background").isEqualTo(background);
        assertThat(hex(icon.getRGB(90, 90))).as("dumbbell bar").isEqualTo(dumbbell);
    }

    private static String hex(int argb) {
        return String.format("%06x", argb & 0xFFFFFF);
    }
}
