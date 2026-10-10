package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import com.boatarde.regatasimulator.util.FileUtils;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuildMemeStepTest {

    private static final String MAGICK_PATH = "unused-magick";

    @TempDir Path directory;

    @Test
    void buildsPerspectiveMaskAndCompositeUsingArgumentArraysAndCleansIntermediateFiles() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        WorkflowDataBag bag = bag(List.of(area(1, 1, true), area(2, 2, false)));

        assertThat(renderer.run(bag)).isEqualTo(WorkflowAction.SEND_MEME_STEP);

        Path output = bag.get(WorkflowDataKey.MEME_FILE, Path.class);
        assertThat(output).exists();
        assertThat(output.getParent()).isNotEqualTo(directory);
        assertThat(renderer.commands.getFirst()).containsSubsequence(MAGICK_PATH, "identify", "-format", "%w %h",
            directory.resolve("template with spaces.png").toString());
        assertThat(renderer.commands).anySatisfy(command -> assertThat(command).contains("Perspective"));
        assertThat(renderer.commands.getLast()).containsSubsequence(
            output.getParent().resolve("distorted_source_1.png").toString(),
            directory.resolve("template with spaces.png").toString(), "-composite",
            output.getParent().resolve("distorted_source_2.png").toString());
        assertThat(directory.resolve("distorted_source_1.png")).doesNotExist();
        assertThat(directory.resolve("distorted_source_2.png")).doesNotExist();
        assertThat(directory.resolve("resized_source.png")).doesNotExist();
        assertThat(directory.resolve("distorted_source_temp.png")).doesNotExist();
        for (InputStream input : renderer.dimensionStreams) {
            verify(input, org.mockito.Mockito.atLeastOnce()).close();
        }
        try (var files = Files.list(output.getParent())) {
            assertThat(files.toList()).containsExactly(output);
        }
        FileUtils.deleteTree(output.getParent());
    }

    @Test
    void processStartFailureCurrentlyEndsWorkflowWithoutAnOutput() throws Exception {
        BuildMemeStep renderer = new BuildMemeStep(MAGICK_PATH) {
            @Override protected Process startProcess(ProcessBuilder builder) throws IOException {
                throw new IOException("synthetic process-start failure");
            }
        };
        WorkflowDataBag bag = bag(List.of(area(1, 1, true)));

        assertThrows(ApplicationFailure.class, () -> renderer.run(bag));
        assertThat(bag.get(WorkflowDataKey.MEME_FILE, Path.class)).isNull();
    }

    @Test
    void sparseAreaIndicesCurrentlyFailDuringComposition() throws Exception {
        // Phase 1 will reject invalid geometry before starting external work.
        FakeRenderer renderer = new FakeRenderer();
        WorkflowDataBag bag = bag(List.of(area(3, 1, true)));

        assertThrows(ApplicationFailure.class, () -> renderer.run(bag));
        assertThat(directory.resolve("distorted_source_3.png")).doesNotExist();
        assertThat(bag.get(WorkflowDataKey.MEME_FILE, Path.class)).isNull();
    }

    @Test
    void sparseSourceSlotsCurrentlyFailBeforeStartingAProcess() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        WorkflowDataBag bag = bag(List.of(area(1, 3, true)));

        assertThrows(ApplicationFailure.class, () -> renderer.run(bag));
        assertThat(renderer.commands).isEmpty();
    }

    @Test
    void duplicateAreaIndicesAreRejectedBeforeExternalWork() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        WorkflowDataBag bag = bag(List.of(area(1, 1, true), area(1, 2, false)));

        assertThrows(ApplicationFailure.class, () -> renderer.run(bag));
        assertThat(renderer.commands).isEmpty();
    }

    @Test
    void nonzeroProcessExitFailsTheWorkflow() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        renderer.exitCode = 7;

        WorkflowDataBag bag = bag(List.of(area(1, 1, true)));
        assertThrows(ApplicationFailure.class, () -> renderer.run(bag));
    }

    @Test
    void interruptedRenderingPreservesTheThreadInterruptFlag() throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("400 300\n".getBytes(StandardCharsets.UTF_8)));
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenThrow(new InterruptedException("synthetic interruption"));
        BuildMemeStep renderer = rendererReturning(process);
        WorkflowDataBag bag = bag(List.of(area(1, 1, true)));

        try {
            assertThrows(ApplicationFailure.class, () -> renderer.run(bag));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(bag.get(WorkflowDataKey.MEME_FILE, Path.class)).isNull();
        } finally {
            Thread.interrupted(); // Clear the flag so it cannot leak into another test.
        }
    }

    @Test
    void emptyIdentifyOutputEndsTheWorkflowAndClosesItsReader() throws Exception {
        Process process = mock(Process.class);
        InputStream input = spy(new ByteArrayInputStream(new byte[0]));
        when(process.getInputStream()).thenReturn(input);
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        WorkflowDataBag bag = bag(List.of(area(1, 1, true)));

        assertThrows(ApplicationFailure.class, () -> rendererReturning(process).run(bag));
        assertThat(bag.get(WorkflowDataKey.MEME_FILE, Path.class)).isNull();
        verify(input, org.mockito.Mockito.atLeastOnce()).close();
    }

    private BuildMemeStep rendererReturning(Process process) {
        return new BuildMemeStep(MAGICK_PATH) {
            @Override protected Process startProcess(ProcessBuilder builder) {
                return process;
            }
        };
    }

    private WorkflowDataBag bag(List<TemplateArea> areas) throws IOException {
        Path template = Files.writeString(directory.resolve("template with spaces.png"), "fake image");
        Path first = Files.writeString(directory.resolve("source one.png"), "fake image");
        Path second = Files.writeString(directory.resolve("source two.png"), "fake image");
        Template metadata = new Template();
        metadata.setAreas(areas);
        WorkflowDataBag bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.TEMPLATE, metadata);
        bag.put(WorkflowDataKey.TEMPLATE_FILE, template);
        bag.put(WorkflowDataKey.SOURCE_FILES, List.of(first, second));
        return bag;
    }

    private TemplateArea area(int index, int source, boolean background) {
        return TemplateArea.builder().index(index).source(source).background(background)
            .topLeft(corner(10, 10)).topRight(corner(90, 10))
            .bottomRight(corner(90, 90)).bottomLeft(corner(10, 90)).build();
    }

    private AreaCorner corner(int x, int y) { return AreaCorner.builder().x(x).y(y).build(); }

    private static class FakeRenderer extends BuildMemeStep {
        final List<List<String>> commands = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<InputStream> dimensionStreams = java.util.Collections.synchronizedList(new ArrayList<>());
        int exitCode;

        FakeRenderer() { super(MAGICK_PATH); }

        @Override
        protected Process startProcess(ProcessBuilder builder) throws IOException {
            List<String> command = List.copyOf(builder.command());
            commands.add(command);
            Process process = mock(Process.class);
            InputStream input = spy(new ByteArrayInputStream("400 300\n".getBytes(StandardCharsets.UTF_8)));
            when(process.getInputStream()).thenReturn(input);
            when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
            when(process.exitValue()).thenReturn(exitCode);
            if (command.contains("identify")) {
                dimensionStreams.add(input);
            }
            try {
                when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (!command.contains("identify")) Files.writeString(Path.of(command.getLast()), "fake rendered image");
            return process;
        }
    }

    @Test
    void concurrentJobsSharingTemplateUseDifferentDirectories() throws Exception {
        WorkflowDataBag first = bag(List.of(area(1, 1, true)));
        WorkflowDataBag second = bag(List.of(area(1, 1, true)));
        FakeRenderer renderer = new FakeRenderer();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> renderer.run(first));
            var b = executor.submit(() -> renderer.run(second));
            assertThat(a.get()).isEqualTo(WorkflowAction.SEND_MEME_STEP);
            assertThat(b.get()).isEqualTo(WorkflowAction.SEND_MEME_STEP);
            Path one = first.get(WorkflowDataKey.MEME_FILE, Path.class);
            Path two = second.get(WorkflowDataKey.MEME_FILE, Path.class);
            assertThat(one).isNotEqualTo(two).exists();
            assertThat(two).exists();
            FileUtils.deleteTree(one.getParent());
            FileUtils.deleteTree(two.getParent());
        }
    }
}