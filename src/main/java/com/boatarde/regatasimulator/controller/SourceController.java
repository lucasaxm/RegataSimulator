package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.SourceResponse;
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
    public ResponseEntity<GalleryResponse<SourceResponse>> getAllSources(@RequestParam(defaultValue = "1") int page,
                                                                         @RequestParam(defaultValue = "12")
                                                                         int perPage) {
        GalleryResponse<SourceResponse> response = sourceService.getSources(page, perPage);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> getImage(@PathVariable UUID id) {
        Resource file = sourceService.loadImageAsResource(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(file);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteImage(@PathVariable UUID id) {
        sourceService.deleteSource(id);
        return ResponseEntity.noContent().build();
    }
}
