package com.boatarde.regatasimulator.adapter.media;

import com.boatarde.regatasimulator.application.ImageRenderer;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.FileUtils;
import com.boatarde.regatasimulator.util.MediaValidation;
import com.boatarde.regatasimulator.util.ProcessRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class ImageMagickRenderer implements ImageRenderer {
    private static final String COMPOSITE = "-composite";
    private static final String LIMIT = "-limit";
    private final String magickPath;
    private final ProcessRunner processRunner = new ProcessRunner(Duration.ofSeconds(30));
    public ImageMagickRenderer(@Value("${magick.path}") String magickPath) { this.magickPath = magickPath; }

    @Override
    public RenderedImage render(Request request) {
        var distortedSources = new ArrayList<Path>();
        Path jobDirectory = null;
        boolean handedOff = false;
        try {
            MediaValidation.geometry(request.areas(), null);
            jobDirectory = Files.createTempDirectory("regata-render-", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
            progress(request, 0);
            int count = request.areas().size();
            for (int i = 0; i < count; i++) {
                TemplateArea area = request.areas().get(i);
                distortedSources.add(i, buildDistortedSource(request.template(), jobDirectory,
                    request.sources().get(area.getSource() - 1), area));
                progress(request, (i + 1) * 100 / (count + 2));
            }
            progress(request, (count + 1) * 100 / (count + 2));
            Path result = compositeFinalImage(request.template(), jobDirectory, distortedSources, request.areas());
            handedOff = true;
            return new RenderedImage(result, jobDirectory);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Meme rendering interrupted", e);
        } catch (IOException | RuntimeException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Meme rendering failed", e);
        } finally {
            distortedSources.forEach(this::deleteTemporaryFile);
            if (!handedOff) FileUtils.deleteTree(jobDirectory);
            else {
                try (var paths = Files.list(jobDirectory)) {
                    paths.filter(path -> !path.getFileName().toString().equals("final_output.png"))
                        .forEach(this::deleteTemporaryFile);
                } catch (IOException e) { log.warn("Could not clean render intermediates"); }
            }
        }
    }

    private void progress(Request request, int value) {
        if (request.progress() != null) request.progress().accept(value);
    }

    protected Process startProcess(ProcessBuilder builder) throws IOException { return builder.start(); }

    private Path buildDistortedSource(Path templateFile, Path templateDir, Path sourceFile, TemplateArea area)
        throws IOException, InterruptedException {
        Path resizedSource = templateDir.resolve("resized_source.png");
        Path distortedSourceTemp = templateDir.resolve("distorted_source_temp.png");
        Path distortedSource = templateDir.resolve("distorted_source_%d.png".formatted(area.getIndex()));
        try {
            ProcessBuilder pb = new ProcessBuilder(magickPath, "identify", "-format", "%w %h", templateFile.toString());
            String[] dimensions = execute(pb, templateDir).strip().split("\\s+");
            if (dimensions.length != 2) throw new IOException("ImageMagick returned invalid image dimensions");
            int width = Integer.parseInt(dimensions[0]);
            int height = Integer.parseInt(dimensions[1]);
            if (width <= 0 || height <= 0 || (long) width * height > 40_000_000) {
                throw new IOException("Image dimensions exceed render limits");
            }
            for (var corner : List.of(area.getTopLeft(), area.getTopRight(), area.getBottomRight(), area.getBottomLeft())) {
                if (corner.getX() > width || corner.getY() > height) throw new IOException("Template corner exceeds image dimensions");
            }
            pb = new ProcessBuilder(magickPath, sourceFile.toString(), "-resize", width + "x" + height + "!", resizedSource.toString());
            execute(pb, templateDir);
            String coordinates = String.format("0,0 %d,%d 0,%d %d,%d %d,0 %d,%d %d,%d %d,%d",
                area.getTopLeft().getX(), area.getTopLeft().getY(), height, area.getBottomLeft().getX(), area.getBottomLeft().getY(),
                width, area.getTopRight().getX(), area.getTopRight().getY(), width, height,
                area.getBottomRight().getX(), area.getBottomRight().getY());
            pb = new ProcessBuilder(magickPath, resizedSource.toString(), "-alpha", "set", "-virtual-pixel", "transparent",
                "-distort", "Perspective", coordinates, distortedSourceTemp.toString());
            execute(pb, templateDir);
            Path mask = templateDir.resolve("mask_%d.png".formatted(area.getIndex()));
            if (!mask.toFile().exists()) {
                String draw = String.format("polygon %d,%d %d,%d %d,%d %d,%d", area.getTopLeft().getX(), area.getTopLeft().getY(),
                    area.getTopRight().getX(), area.getTopRight().getY(), area.getBottomRight().getX(), area.getBottomRight().getY(),
                    area.getBottomLeft().getX(), area.getBottomLeft().getY());
                pb = new ProcessBuilder(magickPath, "-size", width + "x" + height, "xc:black", "-fill", "white", "-draw", draw, mask.toString());
                execute(pb, templateDir);
            }
            pb = new ProcessBuilder(magickPath, distortedSourceTemp.toString(), mask.toString(), "-alpha", "off",
                "-compose", "CopyOpacity", COMPOSITE, distortedSource.toString());
            execute(pb, templateDir);
            return distortedSource;
        } finally { deleteTemporaryFile(resizedSource); deleteTemporaryFile(distortedSourceTemp); }
    }

    private String execute(ProcessBuilder builder, Path jobDirectory) throws IOException, InterruptedException {
        int offset = builder.command().contains("identify") ? 2 : 1;
        builder.command().addAll(offset, List.of(LIMIT, "memory", "128MiB", LIMIT, "map", "256MiB", LIMIT, "disk", "512MiB",
            LIMIT, "thread", "2", LIMIT, "time", "25"));
        builder.directory(jobDirectory.toFile());
        builder.environment().put("MAGICK_TEMPORARY_PATH", jobDirectory.toString());
        return processRunner.run(builder, this::startProcess);
    }

    private void deleteTemporaryFile(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException e) { log.warn("Could not delete temporary render file"); }
    }

    private Path compositeFinalImage(Path templateFile, Path templateDir, List<Path> distortedSources, List<TemplateArea> areas)
        throws InterruptedException, IOException {
        Path finalOutput = templateDir.resolve("final_output.png");
        List<String> command = new ArrayList<>(); command.add(magickPath);
        appendLayers(command, distortedSources, areas, true);
        appendImage(command, templateFile.toString());
        appendLayers(command, distortedSources, areas, false);
        appendImage(command, finalOutput.toString());
        execute(new ProcessBuilder(command), templateDir);
        if (!Files.isRegularFile(finalOutput) || Files.size(finalOutput) == 0) throw new IOException("Image process produced no output");
        return finalOutput;
    }

    private void appendLayers(List<String> command, List<Path> sources, List<TemplateArea> areas, boolean background) {
        for (TemplateArea area : areas) if (area.isBackground() == background) appendImage(command, sources.get(area.getIndex() - 1).toString());
    }

    private void appendImage(List<String> command, String image) {
        if (command.size() > 2) command.add(COMPOSITE);
        command.add(image);
    }
}