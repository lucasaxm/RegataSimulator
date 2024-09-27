package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.GalleryItem;
import com.boatarde.regatasimulator.models.GalleryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SourceService {

    @Value("${regata-simulator.sources.path}")
    private String sourcesPath;

    public GalleryResponse getGalleryItems(int page, int perPage) {
        Path dir = Paths.get(sourcesPath);
        try {
            List<GalleryItem> allItems = Files.list(dir)
                .filter(path -> !Files.isDirectory(path))
                .map(path -> new GalleryItem(path.getFileName().toString(), "sources"))
                .collect(Collectors.toList());

            int totalItems = allItems.size();
            int startIndex = (page - 1) * perPage;
            int endIndex = Math.min(startIndex + perPage, totalItems);

            List<GalleryItem> pageItems = allItems.subList(startIndex, endIndex);

            return new GalleryResponse(pageItems, totalItems);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read source items", e);
        }
    }

    public Resource loadImageAsResource(String filename) {
        try {
            Path filePath = Paths.get(sourcesPath).resolve(filename);
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

    public void deleteImage(String filename) {
        try {
            Path filePath = Paths.get(sourcesPath).resolve(filename);
            Files.delete(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete file: " + filename, e);
        }
    }
}
