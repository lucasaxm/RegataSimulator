package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.factory.ImageTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class MediaValidationTest {
    private static final String HEADER = "Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background\n";
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"image.jpg", "image.png", "image.jpeg"})
    void generatedImagesAreDecodedAndDimensionsReturned(String name) throws IOException {
        assertThat(MediaValidation.image(ImageTestFactory.image(temporary.resolve(name))))
            .isEqualTo(new MediaValidation.Dimensions(400, 300));
    }

    @Test
    void rejectsFakeTruncatedAndOversizedImages() throws Exception {
        Path text = Files.writeString(temporary.resolve("fake.png"), "not an image");
        assertThatThrownBy(() -> MediaValidation.image(text)).isInstanceOf(IOException.class);
        Path valid = ImageTestFactory.image(temporary.resolve("truncated.png"));
        byte[] bytes = Files.readAllBytes(valid);
        Files.write(valid, java.util.Arrays.copyOf(bytes, 40));
        assertThatThrownBy(() -> MediaValidation.image(valid)).isInstanceOf(IOException.class);
        try (var file = new java.io.RandomAccessFile(text.toFile(), "rw")) {
            file.setLength(MediaValidation.MAX_BYTES + 1);
        }
        assertThatThrownBy(() -> MediaValidation.image(text)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsPixelAndDimensionLimitsBeforeDecoding() throws IOException {
        Path wide = temporary.resolve("wide.png");
        ImageIO.write(new BufferedImage(10_001, 1, BufferedImage.TYPE_BYTE_GRAY), "png", wide.toFile());
        assertThatThrownBy(() -> MediaValidation.image(wide)).isInstanceOf(IOException.class)
            .hasMessageContaining("pixel dimensions");
        Path pixels = temporary.resolve("pixels.png");
        ImageIO.write(new BufferedImage(6500, 6500, BufferedImage.TYPE_BYTE_GRAY), "png", pixels.toFile());
        assertThatThrownBy(() -> MediaValidation.image(pixels)).isInstanceOf(IOException.class)
            .hasMessageContaining("pixel dimensions");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0,1,0,0,20,0,20,20,0,20,1", "2,1,0,0,20,0,20,20,0,20,1",
        "1,0,0,0,20,0,20,20,0,20,1", "1,2,0,0,20,0,20,20,0,20,1",
        "1,1,-1,0,20,0,20,20,0,20,1", "1,1,0,0,0,0,20,20,0,20,1",
        "1,1,0,0,20,20,20,0,0,20,1", "1,1,0,0,20,0,20,20,0,20,2"
    })
    void rejectsInvalidSemanticGeometry(String row) {
        assertThatThrownBy(() -> JsonDBUtils.parseTemplateCsv(HEADER + row)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsOutOfImageBoundsAndAreaLimitButAllowsSharedContiguousSlots() throws IOException {
        String row = "1,1,0,0,20,0,20,20,0,20,1";
        var areas = JsonDBUtils.parseTemplateCsv(HEADER + row);
        assertThatThrownBy(() -> MediaValidation.geometry(areas, new MediaValidation.Dimensions(10, 10)))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> JsonDBUtils.parseTemplateCsv(HEADER + (row + "\n").repeat(129)))
            .isInstanceOf(IOException.class).hasMessageContaining("Too many");
        assertThat(JsonDBUtils.parseTemplateCsv(HEADER + row + "\n2,1,20,0,40,0,40,20,20,20,0"))
            .hasSize(2);
    }
}