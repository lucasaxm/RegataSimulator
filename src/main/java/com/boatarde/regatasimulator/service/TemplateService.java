package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.TemplateResponse;
import com.boatarde.regatasimulator.util.TelegramUtils;
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
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TemplateService {

    @Value("${regata-simulator.templates.path}")
    private String templatesPathString;

    public GalleryResponse<TemplateResponse> getTemplates(int page, int perPage) {
        Path dir = Paths.get(templatesPathString);
        try {
            List<TemplateResponse> allItems = Files.list(dir)
                .filter(Files::isDirectory)
                .flatMap(subdir -> {
                    try {
                        return Files.list(subdir)
                            .filter(file -> file.getFileName().toString().equalsIgnoreCase("template.jpg") ||
                                file.getFileName().toString().equalsIgnoreCase("template.png"))
                            .map(file -> {
                                String details = getTemplateDetails(subdir.getFileName().toString());
                                try {
                                    return TemplateResponse.builder()
                                        .id(UUID.fromString(subdir.getFileName().toString()))
                                        .areas(TelegramUtils.parseTemplateCsv(details))
                                        .build();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read template subdirectory", e);
                    }
                })
                .collect(Collectors.toList());

            int totalItems = allItems.size();
            int startIndex = (page - 1) * perPage;
            int endIndex = Math.min(startIndex + perPage, totalItems);

            List<TemplateResponse> pageItems = allItems.subList(startIndex, endIndex);

            return new GalleryResponse<>(pageItems, totalItems);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read template items", e);
        }
    }

    public String getTemplateDetails(String id) {
        Path csvPath = Paths.get(templatesPathString, id, "template.csv");
        try {
            return new String(Files.readAllBytes(csvPath));
        } catch (IOException e) {
            return null;
        }
    }

    public Resource loadImageAsResource(UUID id) {
        Path dir = Paths.get(templatesPathString, id.toString());

        try {
            Path templateFile = dir.resolve("template.jpg");
            if (!templateFile.toFile().exists()) {
                templateFile = dir.resolve("template.png");
            }
            if (!templateFile.toFile().exists()) {
                throw new RuntimeException("Template not found: " + id);
            }
            return new UrlResource(templateFile.toUri());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file.", e);
        }
    }

    public void deleteTemplate(UUID id) {
        try {
            Path filePath = Paths.get(templatesPathString).resolve(id.toString());
            try (Stream<Path> paths = Files.walk(filePath)) {
                paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete template: " + id, e);
        }
    }
}