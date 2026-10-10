package com.boatarde.regatasimulator.adapter.media;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.application.ImageRenderer;
import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.TemplateArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageMagickRendererTest {
    @TempDir Path directory;

    @Test
    void perspectiveMaskLayerOrderArgumentArraysAndOwnedCleanup() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        Path job;
        try (var image = renderer.render(request(List.of(area(1, 1, true), area(2, 2, false))))) {
            job = image.file().getParent();
            assertThat(renderer.commands.getFirst()).containsSubsequence("unused-magick", "identify");
            assertThat(renderer.commands.getFirst()).contains(directory.resolve("template with spaces.png").toString());
            assertThat(renderer.commands).anySatisfy(command -> assertThat(command).contains("Perspective"));
            assertThat(renderer.commands.getLast()).containsSubsequence(job.resolve("distorted_source_1.png").toString(),
                directory.resolve("template with spaces.png").toString(), "-composite", job.resolve("distorted_source_2.png").toString());
            try (var files = Files.list(job)) { assertThat(files.toList()).containsExactly(image.file()); }
            assertThat(job).isNotEqualTo(directory);
            renderer.streams.forEach(input -> { try { verify(input, atLeastOnce()).close(); } catch (IOException e) { fail(e); } });
        }
        assertThat(job).doesNotExist();
        assertThat(directory.resolve("template with spaces.png")).exists();
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "exit", "timeout", "dimensions", "empty", "noOutput", "partial", "interrupt"})
    void failedRenderingCleansWholeJobAndPreservesInterruption(String mode) throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        renderer.mode = mode;
        try {
            assertThrows(ApplicationFailure.class, () -> renderer.render(request(List.of(area(1, 1, true)))));
            assertThat(renderer.jobs).isNotEmpty().allSatisfy(path -> assertThat(path).doesNotExist());
            assertEquals(mode.equals("interrupt"), Thread.currentThread().isInterrupted());
            assertThat(directory.resolve("template with spaces.png")).exists();
        } finally { Thread.interrupted(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sparseArea", "sparseSlot", "duplicate"})
    void invalidGeometryFailsBeforeExternalWork(String mode) throws IOException {
        FakeRenderer renderer = new FakeRenderer();
        List<TemplateArea> areas = switch (mode) {
            case "sparseArea" -> List.of(area(3, 1, true));
            case "sparseSlot" -> List.of(area(1, 3, true));
            default -> List.of(area(1, 1, true), area(1, 2, false));
        };
        assertThrows(ApplicationFailure.class, () -> renderer.render(request(areas)));
        assertThat(renderer.commands).isEmpty();
    }

    @Test
    void concurrentJobsSharingTemplateKeepSeparateOwnership() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        var request = request(List.of(area(1, 1, true)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> renderer.render(request));
            var second = executor.submit(() -> renderer.render(request));
            Path one;
            Path two;
            try (var a = first.get(5, TimeUnit.SECONDS); var b = second.get(5, TimeUnit.SECONDS)) {
                one = a.file(); two = b.file();
                assertThat(one).exists().isNotEqualTo(two);
                assertThat(two).exists();
            }
            assertThat(one.getParent()).doesNotExist();
            assertThat(two.getParent()).doesNotExist();
        }
    }

    private ImageRenderer.Request request(List<TemplateArea> areas) throws IOException {
        return new ImageRenderer.Request(Files.writeString(directory.resolve("template with spaces.png"), "fixture"), areas,
            List.of(Files.writeString(directory.resolve("source one.png"), "fixture"),
                Files.writeString(directory.resolve("source two.png"), "fixture")), null);
    }

    private TemplateArea area(int index, int source, boolean background) {
        return TemplateArea.builder().index(index).source(source).background(background)
            .topLeft(new AreaCorner(10, 10)).topRight(new AreaCorner(90, 10))
            .bottomRight(new AreaCorner(90, 90)).bottomLeft(new AreaCorner(10, 90)).build();
    }

    private static class FakeRenderer extends ImageMagickRenderer {
        final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        final List<Path> jobs = Collections.synchronizedList(new ArrayList<>());
        final List<InputStream> streams = Collections.synchronizedList(new ArrayList<>());
        String mode = "success";
        FakeRenderer() { super("unused-magick"); }

        @Override protected Process startProcess(ProcessBuilder builder) throws IOException {
            var command = List.copyOf(builder.command());
            commands.add(command); jobs.add(builder.directory().toPath());
            if (mode.equals("start") || mode.equals("partial") && commands.size() == 4) throw new IOException("fixture failure");
            Process process = mock(Process.class);
            String dimensions = switch (mode) {
                case "dimensions" -> "400";
                case "empty" -> "";
                default -> "400 300";
            };
            InputStream input = spy(new ByteArrayInputStream(dimensions.getBytes(StandardCharsets.UTF_8)));
            streams.add(input);
            when(process.getInputStream()).thenReturn(input);
            when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
            when(process.exitValue()).thenReturn(mode.equals("exit") ? 7 : 0);
            try {
                if (mode.equals("interrupt")) when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException());
                else when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(!mode.equals("timeout"));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            if (!mode.equals("noOutput") && !command.contains("identify")) Files.writeString(Path.of(command.getLast()), "fixture output");
            return process;
        }
    }
}