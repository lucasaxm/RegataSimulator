package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.GalleryItem;
import com.boatarde.regatasimulator.models.GalleryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TemplateService {

    @Value("${regata-simulator.templates.path}")
    private String templatesPath;

    public GalleryResponse getGalleryItems(int page, int perPage) {
        Path dir = Paths.get(templatesPath);
        try {
            List<GalleryItem> allItems = Files.list(dir)
                .filter(Files::isDirectory)
                .flatMap(subdir -> {
                    try {
                        return Files.list(subdir)
                            .filter(file -> file.getFileName().toString().endsWith(".jpg") ||
                                file.getFileName().toString().endsWith(".png"))
                            .map(file -> new GalleryItem(subdir.getFileName().toString() + "_" + file.getFileName().toString(), "templates"));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read template subdirectory", e);
                    }
                })
                .collect(Collectors.toList());

            int totalItems = allItems.size();
            int startIndex = (page - 1) * perPage;
            int endIndex = Math.min(startIndex + perPage, totalItems);

            List<GalleryItem> pageItems = allItems.subList(startIndex, endIndex);

            return new GalleryResponse(pageItems, totalItems);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read template items", e);
        }
    }

    public String getTemplateDetails(String id) {
        Path csvPath = Paths.get(templatesPath, id, "template.csv");
        try {
            return new String(Files.readAllBytes(csvPath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read template details", e);
        }
    }

    public Resource loadImageAsResource(String filename) {
        try {
            String[] parts = filename.split("_", 2);
            Path filePath = Paths.get(templatesPath, parts[0], parts[1]);
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists()) {
                return resource;
            } else {
                throw new RuntimeException("File not found: " + filename);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file: " + filename, e);
        }
    }

    public void deleteTemplate(String id) {
        try {
            Path templateDir = Paths.get(templatesPath).resolve(id);
            Files.walk(templateDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete template: " + id, e);
        }
    }
}