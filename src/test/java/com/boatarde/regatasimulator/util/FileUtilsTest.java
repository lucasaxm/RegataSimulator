package com.boatarde.regatasimulator.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.*;

class FileUtilsTest {
    @TempDir Path temporary;

    @Test
    void archivesPreserveWholeDirectoriesAndRestoreTheirBytes() throws Exception {
        Path input = Files.createDirectory(temporary.resolve("input"));
        Path output = Files.createDirectory(temporary.resolve("archives"));
        Path nested = Files.createDirectory(input.resolve("uuid"));
        byte[] bytes = new byte[1500];
        java.util.Arrays.fill(bytes, (byte) 42);
        Files.write(nested.resolve("source.png"), bytes);
        Files.write(input.resolve("metadata.json"), bytes);
        var archives = FileUtils.zipInChunks(input.toString(), 2000, output);
        assertThat(archives).hasSize(2);
        try {
            for (Path path : archives) {
                try (var archive = new ZipFile(path.toFile())) {
                    var entries = archive.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (!entry.isDirectory()) {
                            try (var stream = archive.getInputStream(entry)) {
                                assertThat(stream.readAllBytes()).isEqualTo(bytes);
                            }
                        }
                    }
                }
            }
        } finally {
            for (Path path : archives) Files.delete(path);
        }
    }

    @Test
    void oversizedItemAndSymlinkAreRejectedWithoutArchives() throws IOException {
        Path input = Files.createDirectory(temporary.resolve("input"));
        Path output = Files.createDirectory(temporary.resolve("archives"));
        Files.write(input.resolve("large"), new byte[1025]);
        assertThatThrownBy(() -> FileUtils.zipInChunks(input.toString(), 1024, output))
            .isInstanceOf(IOException.class).hasMessageContaining("item exceeds");
        Files.delete(input.resolve("large"));
        Files.createSymbolicLink(input.resolve("link"), output);
        assertThatThrownBy(() -> FileUtils.zipInChunks(input.toString(), 1024, output)).isInstanceOf(IOException.class);
        try (var paths = Files.list(output)) { assertThat(paths.toList()).isEmpty(); }
    }

    @Test
    void encodedArchiveOverflowClosesStreamsAndDeletesAllPreparedArchives() throws IOException {
        Path input = Files.createDirectory(temporary.resolve("input"));
        Path output = Files.createDirectory(temporary.resolve("archives"));
        Files.write(input.resolve("a"), new byte[100]);
        byte[] random = new byte[900];
        new java.util.Random(42).nextBytes(random);
        Files.write(input.resolve("b"), random);
        assertThatThrownBy(() -> FileUtils.zipInChunks(input.toString(), 950, output))
            .isInstanceOf(IOException.class).hasMessageContaining("Encoded archive");
        try (var paths = Files.list(output)) { assertThat(paths.toList()).isEmpty(); }
    }
}