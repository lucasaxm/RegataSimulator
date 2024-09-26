package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.TemplateArea;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BUILD_MEME_STEP)
public class BuildMemeStep implements WorkflowStep {

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        List<Path> sourceFiles = bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);
        Path templateFile = bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class);
        List<TemplateArea> templateAreaList =
            bag.getGeneric(WorkflowDataKey.TEMPLATE_AREAS, List.class, TemplateArea.class);

        try {
            Path result = templateFile;
            for (int i = 0; i < sourceFiles.size(); i++) {
                result = buildMeme(result, sourceFiles.get(i), templateAreaList.get(i));
            }
            bag.put(WorkflowDataKey.MEME_FILE, result);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            return WorkflowAction.NONE;
        }

        return WorkflowAction.SEND_MEME_STEP;
    }

    private Path buildMeme(Path templateFile, Path sourceFile, TemplateArea templateArea) throws Exception {
        log.info("running command: magick identify -format %w %h {}", templateFile.toString());
        ProcessBuilder pb = new ProcessBuilder("magick", "identify", "-format", "%w %h", templateFile.toString());
        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String[] dimensions = reader.readLine().split(" ");
        int width = Integer.parseInt(dimensions[0]);
        int height = Integer.parseInt(dimensions[1]);
        process.waitFor();

        // Resize source image
        Path resizedSource = Paths.get("resized_source.png");
        log.info("running command: magick {} -resize {}x{}! {}",
            sourceFile.toString(), width, height, resizedSource);
        pb = new ProcessBuilder("magick", sourceFile.toString(), "-resize", width + "x" + height + "!",
            resizedSource.toString());
        pb.start().waitFor();

        // Distort source image
        Path distortedSourceTemp = Paths.get("distorted_source_temp.png");
        String coordinates = String.format("0,0 %d,%d 0,%d %d,%d %d,0 %d,%d %d,%d %d,%d",
            templateArea.getTopLeft().getX(), templateArea.getTopLeft().getY(),
            height, templateArea.getBottomLeft().getX(), templateArea.getBottomLeft().getY(),
            width, templateArea.getTopRight().getX(), templateArea.getTopRight().getY(),
            width, height, templateArea.getBottomRight().getX(), templateArea.getBottomRight().getY());
        log.info("running command: magick {} -alpha set -virtual-pixel transparent -distort Perspective {} {}",
            resizedSource, coordinates, distortedSourceTemp);
        pb = new ProcessBuilder("magick", resizedSource.toString(), "-alpha", "set", "-virtual-pixel", "transparent",
            "-distort", "Perspective", coordinates, distortedSourceTemp.toString());
        pb.start().waitFor();

        // Create mask
        Path mask = Paths.get("mask.png");
        String drawCommand = String.format("polygon %d,%d %d,%d %d,%d %d,%d",
            templateArea.getTopLeft().getX(), templateArea.getTopLeft().getY(),
            templateArea.getTopRight().getX(), templateArea.getTopRight().getY(),
            templateArea.getBottomRight().getX(), templateArea.getBottomRight().getY(),
            templateArea.getBottomLeft().getX(), templateArea.getBottomLeft().getY());
        log.info("running command: magick -size {}x{} xc:black -fill white -draw {} {}", width, height, drawCommand,
            mask);
        pb = new ProcessBuilder("magick", "-size", width + "x" + height, "xc:black", "-fill", "white",
            "-draw", drawCommand, mask.toString());
        pb.start().waitFor();

        // Apply mask to distorted source
        Path distortedSource = Paths.get("distorted_source.png");
        log.info("running command: magick {} {} -alpha off -compose CopyOpacity -composite {}",
            distortedSourceTemp, mask, distortedSource);
        pb = new ProcessBuilder("magick", distortedSourceTemp.toString(), mask.toString(), "-alpha", "off",
            "-compose", "CopyOpacity", "-composite", distortedSource.toString());
        pb.start().waitFor();

        // Composite final image
        Path finalOutput = Paths.get("final_output.png");
        log.info("running command: magick composite -compose Over {} {} {}", distortedSource, templateFile,
            finalOutput);
        pb = new ProcessBuilder("magick", "composite", "-compose", "Over", distortedSource.toString(),
            templateFile.toString(), finalOutput.toString());
        pb.start().waitFor();

        return finalOutput;
    }

}
