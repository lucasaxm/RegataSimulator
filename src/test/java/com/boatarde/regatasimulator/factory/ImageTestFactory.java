package com.boatarde.regatasimulator.factory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

public final class ImageTestFactory {
    private ImageTestFactory() { }

    public static Path image(Path path) throws IOException {
        String format = path.toString().endsWith(".png") ? "png" : "jpg";
        BufferedImage image = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        if (!ImageIO.write(image, format, path.toFile())) {
            throw new IOException("No synthetic image writer");
        }
        image.flush();
        return path;
    }
}