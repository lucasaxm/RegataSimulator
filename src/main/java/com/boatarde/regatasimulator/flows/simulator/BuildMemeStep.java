package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import com.boatarde.regatasimulator.util.FileUtils;
import com.boatarde.regatasimulator.util.ProcessRunner;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BUILD_MEME_STEP)
public class BuildMemeStep implements WorkflowStep {

    private static final String COMPOSITE = "-composite";
    private static final String LIMIT = "-limit";
    private final String magickPath;
    private final ProcessRunner processRunner = new ProcessRunner(Duration.ofSeconds(30));

    public BuildMemeStep(@Value("${magick.path}") String magickPath) {
        this.magickPath = magickPath;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        editCreatingTemplateMessage(bag, 0);

        List<Path> sourceFiles = bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);

        Path templateFile = bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class);
        Template template = bag.get(WorkflowDataKey.TEMPLATE, Template.class);

        var distortedSources = new ArrayList<Path>();
        Path jobDirectory = null;
        boolean handedOff = false;
        try {
            jobDirectory = Files.createTempDirectory("regata-render-",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            int templateAreasCount = template.getAreas().size();
            for (int i = 0; i < templateAreasCount; i++) {
                TemplateArea templateArea = template.getAreas().get(i);
                distortedSources.add(i,
                    buildDistortedSource(templateFile, jobDirectory, sourceFiles.get(templateArea.getSource() - 1), templateArea));
                int progress = (i + 1) * 100 / (templateAreasCount + 2);
                editCreatingTemplateMessage(bag, progress);
            }
            int progress = (templateAreasCount + 1) * 100 / (templateAreasCount + 2);
            editCreatingTemplateMessage(bag, progress);
            Path result =
                compositeFinalImage(templateFile, jobDirectory, distortedSources, template.getAreas());
            bag.put(WorkflowDataKey.MEME_FILE, result);
            bag.put(WorkflowDataKey.RENDER_JOB_DIRECTORY, jobDirectory);
            handedOff = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Meme rendering interrupted", e);
            return WorkflowAction.NONE;
        } catch (IOException | RuntimeException e) {
            log.error(e.getLocalizedMessage(), e);
            return WorkflowAction.NONE;
        } finally {
            distortedSources.forEach(this::deleteTemporaryFile);
            if (!handedOff) {
                FileUtils.deleteTree(jobDirectory);
            } else {
                try (var paths = Files.list(jobDirectory)) {
                    paths.filter(path -> !path.getFileName().toString().equals("final_output.png"))
                        .forEach(this::deleteTemporaryFile);
                } catch (IOException e) {
                    log.warn("Could not clean render intermediates");
                }
            }
        }

        return WorkflowAction.SEND_MEME_STEP;
    }

    private void editCreatingTemplateMessage(WorkflowDataBag bag, int progress) {
        Message creatingTemplateMessage = bag.get(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, Message.class);
        if (creatingTemplateMessage != null) {
            try {
                bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class)
                    .execute(EditMessageText.builder()
                        .chatId(creatingTemplateMessage.getChatId())
                        .messageId(creatingTemplateMessage.getMessageId())
                        .text("Gerando meme de teste...%n<code>%s</code>".formatted(generateProgressBar(progress)))
                        .parseMode("HTML")
                        .build());
            } catch (TelegramApiException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        }
    }

    private String generateProgressBar(int progress) {
        int totalBars = 20;
        int filledBars = (progress * totalBars) / 100;
        int emptyBars = totalBars - filledBars;
        return "[" + "=".repeat(filledBars) + " ".repeat(emptyBars) + "] " + progress + "%";
    }

    protected Process startProcess(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    private Path buildDistortedSource(Path templateFile, Path templateDir, Path sourceFile, TemplateArea templateArea)
        throws IOException, InterruptedException {
        Path resizedSource = templateDir.resolve("resized_source.png");
        Path distortedSourceTemp = templateDir.resolve("distorted_source_temp.png");
        Path distortedSource = templateDir.resolve("distorted_source_%d.png".formatted(templateArea.getIndex()));

        try {
            log.info("running command: {} identify -format %w %h {}", magickPath, templateFile);
            ProcessBuilder pb = new ProcessBuilder(magickPath, "identify", "-format", "%w %h", templateFile.toString());
            String[] dimensions = execute(pb, templateDir).strip().split("\\s+");
            if (dimensions.length != 2) {
                throw new IOException("ImageMagick returned invalid image dimensions");
            }
            int width = Integer.parseInt(dimensions[0]);
            int height = Integer.parseInt(dimensions[1]);
            if (width <= 0 || height <= 0 || (long) width * height > 40_000_000) {
                throw new IOException("Image dimensions exceed render limits");
            }

            // Resize source image
            log.info("running command: {} {} -resize {}x{}! {}", magickPath, sourceFile.toString(), width, height,
                resizedSource);
            pb = new ProcessBuilder(magickPath, sourceFile.toString(), "-resize", width + "x" + height + "!",
                resizedSource.toString());
            execute(pb, templateDir);

            // Distort source image
            String coordinates = String.format("0,0 %d,%d 0,%d %d,%d %d,0 %d,%d %d,%d %d,%d",
                templateArea.getTopLeft().getX(), templateArea.getTopLeft().getY(),
                height, templateArea.getBottomLeft().getX(), templateArea.getBottomLeft().getY(),
                width, templateArea.getTopRight().getX(), templateArea.getTopRight().getY(),
                width, height, templateArea.getBottomRight().getX(), templateArea.getBottomRight().getY());
            log.info("running command: {} {} -alpha set -virtual-pixel transparent -distort Perspective {} {}",
                magickPath, resizedSource, coordinates, distortedSourceTemp);
            pb = new ProcessBuilder(magickPath, resizedSource.toString(), "-alpha", "set", "-virtual-pixel",
                "transparent",
                "-distort", "Perspective", coordinates, distortedSourceTemp.toString());
            execute(pb, templateDir);

            // Create mask
            Path mask = templateDir.resolve(String.format("mask_%d.png", templateArea.getIndex()));
            if (!mask.toFile().exists()) {
                String drawCommand = String.format("polygon %d,%d %d,%d %d,%d %d,%d",
                    templateArea.getTopLeft().getX(), templateArea.getTopLeft().getY(),
                    templateArea.getTopRight().getX(), templateArea.getTopRight().getY(),
                    templateArea.getBottomRight().getX(), templateArea.getBottomRight().getY(),
                    templateArea.getBottomLeft().getX(), templateArea.getBottomLeft().getY());
                log.info("Mask not found: running command: {} -size {}x{} xc:black -fill white -draw {} {}", magickPath,
                    width, height,
                    drawCommand, mask);
                pb = new ProcessBuilder(magickPath, "-size", width + "x" + height, "xc:black", "-fill", "white",
                    "-draw", drawCommand, mask.toString());
                execute(pb, templateDir);
            }

            // Apply mask to distorted source
            log.info("running command: {} {} {} -alpha off -compose CopyOpacity -composite {}", magickPath,
                distortedSourceTemp, mask, distortedSource);
            pb = new ProcessBuilder(magickPath, distortedSourceTemp.toString(), mask.toString(), "-alpha", "off",
                "-compose", "CopyOpacity", COMPOSITE, distortedSource.toString());
            execute(pb, templateDir);

            return distortedSource;
        } finally {
            deleteTemporaryFile(resizedSource);
            deleteTemporaryFile(distortedSourceTemp);
        }
    }

    private String execute(ProcessBuilder builder, Path jobDirectory) throws IOException, InterruptedException {
        // Global options belong after the identify subcommand, otherwise directly after magick.
        int offset = builder.command().contains("identify") ? 2 : 1;
        builder.command().addAll(offset, List.of(LIMIT, "memory", "128MiB", LIMIT, "map", "256MiB",
            LIMIT, "disk", "512MiB", LIMIT, "thread", "2", LIMIT, "time", "25"));
        builder.directory(jobDirectory.toFile());
        builder.environment().put("MAGICK_TEMPORARY_PATH", jobDirectory.toString());
        return processRunner.run(builder, this::startProcess);
    }

    private void deleteTemporaryFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete temporary render file: {}", path, e);
        }
    }

    private Path compositeFinalImage(Path templateFile, Path templateDir, List<Path> distortedSources,
                                     List<TemplateArea> templateAreaList)
        throws InterruptedException, IOException {
        // Composite final image
        Path finalOutput = templateDir.resolve("final_output.png");

        List<String> command = new ArrayList<>();
        command.add(magickPath);

        appendLayers(command, distortedSources, templateAreaList, true);
        appendImage(command, templateFile.toString());
        appendLayers(command, distortedSources, templateAreaList, false);
        appendImage(command, finalOutput.toString());

        log.info("running command: {}", command);
        ProcessBuilder pb = new ProcessBuilder(command);
        execute(pb, templateDir);
        if (!Files.isRegularFile(finalOutput) || Files.size(finalOutput) == 0) {
            throw new IOException("Image process produced no output");
        }

        return finalOutput;
    }

    private void appendLayers(List<String> command, List<Path> distortedSources, List<TemplateArea> areas,
                              boolean background) {
        for (TemplateArea area : areas) {
            if (area.isBackground() == background) {
                appendImage(command, distortedSources.get(area.getIndex() - 1).toString());
            }
        }
    }

    private void appendImage(List<String> command, String image) {
        if (command.size() > 2) {
            command.add(COMPOSITE);
        }
        command.add(image);
    }
}
