package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.flows.ApplicationFailure;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class ImageResponse {
    private ImageResponse() { }

    static ResponseEntity<Resource> of(Resource resource) {
        if (!resource.exists()) {
            throw new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "Media not found");
        }
        try (InputStream input = resource.getInputStream(); ImageInputStream image = ImageIO.createImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) {
                throw new IOException("Unrecognized stored image");
            }
            var reader = readers.next();
            try {
                MediaType type = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "png" -> MediaType.IMAGE_PNG;
                    case "jpeg", "jpg" -> MediaType.IMAGE_JPEG;
                    default -> throw new IOException("Unsupported stored image");
                };
                return ResponseEntity.ok().contentType(type).body(resource);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Cannot read stored image", e);
        }
    }
}