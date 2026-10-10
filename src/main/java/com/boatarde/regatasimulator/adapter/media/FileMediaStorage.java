package com.boatarde.regatasimulator.adapter.media;

import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.util.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class FileMediaStorage implements MediaStorage {
    private final Path sources;
    private final Path templates;
    public FileMediaStorage(@Value("${regata-simulator.sources.path}") String sources,
                            @Value("${regata-simulator.templates.path}") String templates) {
        this.sources = Path.of(sources); this.templates = Path.of(templates);
    }

    @Override
    public Path image(Kind kind, UUID id) {
        String base = kind == Kind.SOURCE ? "source" : "template";
        return FileUtils.getFirstExistingFile(directory(kind, id), base + ".jpg", base + ".jpeg", base + ".png")
            .orElseThrow(() -> new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, "Media unavailable"));
    }

    @Override
    public Path prepare(Kind kind, UUID id) {
        try { return Files.createDirectories(directory(kind, id)); }
        catch (IOException e) { throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Media preparation failed", e); }
    }

    @Override
    public void delete(Kind kind, UUID id) {
        Path directory = directory(kind, id);
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        } catch (IOException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Media deletion failed", e);
        }
    }

    private Path directory(Kind kind, UUID id) { return (kind == Kind.SOURCE ? sources : templates).resolve(id.toString()); }
}