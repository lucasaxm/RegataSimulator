package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.Coordinates;
import com.boatarde.regatasimulator.models.Picture;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Scanner;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.BUILD_MEME_STEP)
public class BuildMemeStep implements WorkflowStep {

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        Path templateDir = bag.get(WorkflowDataKey.TEMPLATE_DIRECTORY, Path.class);
        Picture sourceFile = bag.get(WorkflowDataKey.SOURCE_FILE, Picture.class);
        try {
            Coordinates coordinates = readCoordinates(templateDir);
            Picture templateFile = new Picture(templateDir.resolve("Template.jpg").toFile());
            resizeSource(sourceFile, templateDir);
            Picture meme = buildMeme(sourceFile, templateFile, coordinates);
            bag.put(WorkflowDataKey.MEME_FILE, meme);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            return WorkflowAction.NONE;
        }

        return WorkflowAction.SEND_MEME_STEP;
    }

    private void resizeSource(Picture sourceFile, Path templateDir) {
        Picture templateSourceSize = new Picture(new File(templateDir.toFile(), "TemplateSourceSize.jpg"));
        sourceFile.resize(templateSourceSize.width(), templateSourceSize.height());
    }

    private Coordinates readCoordinates(Path templateDir) throws Exception {
        Coordinates coordinates = new Coordinates();
        try (Scanner sc = new Scanner(new File(templateDir.toFile(), "TemplateCoords.txt"))) {
            for (int i = 0; sc.hasNextLine(); i++) {
                if (i == 0) {
                    coordinates.setX(((int) Math.round(Double.parseDouble(sc.nextLine()))));
                } else if (i == 1) {
                    coordinates.setY(((int) Math.round(Double.parseDouble(sc.nextLine()))));
                } else if (i == 2) {
                    coordinates.setOverlay(Objects.equals(sc.nextLine(), "1"));
                } else {
                    throw new RuntimeException("Invalid number of lines in TemplateCoords.txt");
                }
            }
        }
        if (coordinates.getX() == null || coordinates.getY() == null) {
            throw new RuntimeException("Invalid number of lines in TemplateCoords.txt");
        }
        return coordinates;
    }

    private Picture buildMeme(Picture sourceFile, Picture templateFile, Coordinates coordinates) {
        BufferedImage result = layer(sourceFile.getImage(), templateFile.getImage(), coordinates);
        return new Picture(result);
    }

    private BufferedImage layer(Image source, Image template, Coordinates coordinates) {
        BufferedImage result =
            new BufferedImage(template.getWidth(null), template.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics resultgraphics = result.getGraphics();
        resultgraphics.drawImage(template, 0, 0, null);
        resultgraphics.drawImage(source, coordinates.getX(), coordinates.getY(), null);
        return result;
    }
}
