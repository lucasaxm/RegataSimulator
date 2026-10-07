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

import static org.assertj.core.api.Assertions.assertThat;
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
        assertThat(output).isEqualTo(directory.resolve("final_output.png")).exists();
        assertThat(renderer.commands.getFirst()).containsExactly(MAGICK_PATH, "identify", "-format", "%w %h",
            directory.resolve("template with spaces.png").toString());
        assertThat(renderer.commands).anySatisfy(command -> assertThat(command).contains("Perspective"));
        assertThat(renderer.commands.getLast()).containsSubsequence(
            directory.resolve("distorted_source_1.png").toString(),
            directory.resolve("template with spaces.png").toString(), "-composite",
            directory.resolve("distorted_source_2.png").toString());
        assertThat(directory.resolve("distorted_source_1.png")).doesNotExist();
        assertThat(directory.resolve("distorted_source_2.png")).doesNotExist();
        assertThat(directory.resolve("resized_source.png")).doesNotExist();
        assertThat(directory.resolve("distorted_source_temp.png")).doesNotExist();
        for (InputStream input : renderer.dimensionStreams) {
            verify(input).close();
        }
    }

    @Test
    void processStartFailureCurrentlyEndsWorkflowWithoutAnOutput() throws Exception {
        BuildMemeStep renderer = new BuildMemeStep(MAGICK_PATH) {
            @Override protected Process startProcess(ProcessBuilder builder) throws IOException {
                throw new IOException("synthetic process-start failure");
            }
        };
        WorkflowDataBag bag = bag(List.of(area(1, 1, true)));

        assertThat(renderer.run(bag)).isEqualTo(WorkflowAction.NONE);
        assertThat(bag.get(WorkflowDataKey.MEME_FILE, Path.class)).isNull();
    }

    @Test
    void sparseAreaIndicesCurrentlyFailDuringComposition() throws Exception {
        // Phase 1 will reject invalid geometry before starting external work.
        FakeRenderer renderer = new FakeRenderer();
        WorkflowDataBag bag = bag(List.of(area(3, 1, true)));

        assertThat(renderer.run(bag)).isEqualTo(WorkflowAction.NONE);
        assertThat(directory.resolve("distorted_source_3.png")).doesNotExist();
        assertThat(bag.get(WorkflowDataKey.MEME_FILE, Path.class)).isNull();
    }

    @Test
    void sparseSourceSlotsCurrentlyFailBeforeStartingAProcess() throws Exception {
        FakeRenderer renderer = new FakeRenderer();
        WorkflowDataBag bag = bag(List.of(area(1, 3, true)));

        assertThat(renderer.run(bag)).isEqualTo(WorkflowAction.NONE);
        assertThat(renderer.commands).isEmpty();
    }

    @Test
    void duplicateAreaIndicesCurrentlyReuseTheSameScratchPath() throws Exception {
        // Documents current collision-prone behavior; isolation/validation belongs to Phase 1.
        FakeRenderer renderer = new FakeRenderer();
        WorkflowDataBag bag = bag(List.of(area(1, 1, true), area(1, 2, false)));

        assertThat(renderer.run(bag)).isEqualTo(WorkflowAction.SEND_MEME_STEP);
        assertThat(renderer.commands.getLast()).filteredOn(value -> value.endsWith("distorted_source_1.png"))
            .hasSize(2);
    }

    @Test
    void nonzeroProcessExitCurrentlyDoesNotFailTheWorkflow() throws Exception {
        // Phase 1 will check exit codes; no real process is executed here.
        FakeRenderer renderer = new FakeRenderer();
        renderer.exitCode = 7;

        assertThat(renderer.run(bag(List.of(area(1, 1, true))))).isEqualTo(WorkflowAction.SEND_MEME_STEP);
    }

    @Test
    void interruptedRenderingPreservesTheThreadInterruptFlag() throws Exception {
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("400 300\n".getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor()).thenThrow(new InterruptedException("synthetic interruption"));
        BuildMemeStep renderer = rendererReturning(process);
        WorkflowDataBag bag = bag(List.of(area(1, 1, true)));

        try {
            assertThat(renderer.run(bag)).isEqualTo(WorkflowAction.NONE);
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
        WorkflowDataBag bag = bag(List.of(area(1, 1, true)));

        assertThat(rendererReturning(process).run(bag)).isEqualTo(WorkflowAction.NONE);
        assertThat(bag.get(WorkflowDataKey.MEME_FILE, Path.class)).isNull();
        verify(input).close();
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
        final List<List<String>> commands = new ArrayList<>();
        final List<InputStream> dimensionStreams = new ArrayList<>();
        int exitCode;

        FakeRenderer() { super(MAGICK_PATH); }

        @Override
        protected Process startProcess(ProcessBuilder builder) throws IOException {
            List<String> command = List.copyOf(builder.command());
            commands.add(command);
            Process process = mock(Process.class);
            InputStream input = spy(new ByteArrayInputStream("400 300\n".getBytes(StandardCharsets.UTF_8)));
            when(process.getInputStream()).thenReturn(input);
            if (command.contains("identify")) {
                dimensionStreams.add(input);
            }
            try {
                when(process.waitFor()).thenReturn(exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (!command.contains("identify")) Files.writeString(Path.of(command.getLast()), "fake rendered image");
            return process;
        }
    }
}