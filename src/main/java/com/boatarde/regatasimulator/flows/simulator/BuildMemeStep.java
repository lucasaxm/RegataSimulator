package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.TemplateArea;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BUILD_MEME_STEP)
public class BuildMemeStep implements WorkflowStep {

    private final String magickPath;

    public BuildMemeStep(@Value("${magick.path}") String magickPath) {
        this.magickPath = magickPath;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        List<Path> sourceFiles = bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);
        Path templateFile = bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class);
        List<TemplateArea> templateAreaList =
            bag.getGeneric(WorkflowDataKey.TEMPLATE_AREAS, List.class, TemplateArea.class);
        var distortedSources = new ArrayList<Path>();
        try {
            for (int i = 0; i < sourceFiles.size(); i++) {
                distortedSources.add(i,
                    buildDistortedSource(templateFile, sourceFiles.get(i), templateAreaList.get(i)));
            }
            Path result =
                compositeFinalImage(templateFile, templateFile.getParent(), distortedSources, templateAreaList);
            bag.put(WorkflowDataKey.MEME_FILE, result);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            return WorkflowAction.NONE;
        } finally {
            distortedSources.forEach(p -> p.toFile().delete());
        }

        return WorkflowAction.SEND_MEME_STEP;
    }

    private Path buildDistortedSource(Path templateFile, Path sourceFile, TemplateArea templateArea) throws Exception {
        Path templateDir = templateFile.getParent();
        Path resizedSource = templateDir.resolve("resized_source.png");
        Path distortedSourceTemp = templateDir.resolve("distorted_source_temp.png");
        Path distortedSource = templateDir.resolve("distorted_source_%d.png".formatted(templateArea.getIndex()));

        try {
            log.info("running command: {} identify -format %w %h {}", magickPath, templateFile);
            ProcessBuilder pb = new ProcessBuilder(magickPath, "identify", "-format", "%w %h", templateFile.toString());
            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String[] dimensions = reader.readLine().split(" ");
            int width = Integer.parseInt(dimensions[0]);
            int height = Integer.parseInt(dimensions[1]);
            process.waitFor();

            // Resize source image
            log.info("running command: {} {} -resize {}x{}! {}", magickPath, sourceFile.toString(), width, height,
                resizedSource);
            pb = new ProcessBuilder(magickPath, sourceFile.toString(), "-resize", width + "x" + height + "!",
                resizedSource.toString());
            pb.start().waitFor();

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
            pb.start().waitFor();

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
                pb.start().waitFor();
            }

            // Apply mask to distorted source
            log.info("running command: {} {} {} -alpha off -compose CopyOpacity -composite {}", magickPath,
                distortedSourceTemp, mask, distortedSource);
            pb = new ProcessBuilder(magickPath, distortedSourceTemp.toString(), mask.toString(), "-alpha", "off",
                "-compose", "CopyOpacity", "-composite", distortedSource.toString());
            pb.start().waitFor();

            return distortedSource;
        } finally {
            resizedSource.toFile().delete();
            distortedSourceTemp.toFile().delete();
        }
    }

    private Path compositeFinalImage(Path templateFile, Path templateDir, List<Path> distortedSources,
                                     List<TemplateArea> templateAreaList)
        throws InterruptedException, IOException {
        ProcessBuilder pb;
        // Composite final image
        Path finalOutput = templateDir.resolve("final_output.png");

        List<String> command = new ArrayList<>();
        command.add(magickPath);

        templateAreaList.stream().filter(TemplateArea::isBackground).forEach(templateArea -> {
            if (command.size() > 2) {
                command.add("-composite");
            }
            command.add(distortedSources.get(templateArea.getIndex() - 1).toString());
        });

        if (command.size() > 2) {
            command.add("-composite");
        }
        command.add(templateFile.toString());

        templateAreaList.stream().filter(area -> !area.isBackground()).forEach(templateArea -> {
            if (command.size() > 2) {
                command.add("-composite");
            }
            command.add(distortedSources.get(templateArea.getIndex() - 1).toString());
        });

        if (command.size() > 2) {
            command.add("-composite");
        }
        command.add(finalOutput.toString());

        log.info("running command: {}", String.join(" ", command));
        pb = new ProcessBuilder(command.toArray(new String[0]));
        pb.start().waitFor();

        return finalOutput;
    }

}
