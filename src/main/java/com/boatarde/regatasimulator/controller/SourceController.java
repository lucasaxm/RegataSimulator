package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.service.SourceService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    public ResponseEntity<GalleryResponse<Source>> getAllSources(@RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "12") int perPage,
                                                                 @RequestParam(required = false) Status status,
                                                                 @RequestParam(required = false) Long userId) {
        GalleryResponse<Source> response = sourceService.getSources(page, perPage, status, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}.png")
    public ResponseEntity<Resource> getSourceImage(@PathVariable UUID id) {
        Source source = getSource(id);
        Resource file = sourceService.loadSourceAsResource(source);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }

    @GetMapping("/{id}.json")
    public ResponseEntity<Source> getSourceJson(@PathVariable UUID id) {
        Source source = getSource(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(source);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        sourceService.deleteSource(getSource(id));
        return ResponseEntity.noContent().build();
    }

    private Source getSource(UUID id) {
        return sourceService.getSource(id)
            .orElseThrow(() -> new RuntimeException("Source not found: " + id));
    }
}
