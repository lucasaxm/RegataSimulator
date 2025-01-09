package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class TemplateService {

    private final String templatesPathString;
    private final JsonDBTemplate jsonDBTemplate;

    @Value("${regata-simulator.templates.initial-weight}")
    private int initialWeight;

    public TemplateService(@Value("${regata-simulator.templates.path}") String templatesPathString,
                           JsonDBTemplate jsonDBTemplate) {
        this.templatesPathString = templatesPathString;
        this.jsonDBTemplate = jsonDBTemplate;
    }

    public GalleryResponse<Template> getTemplates(int page, int perPage, Status status, Long userId) {
        String jxQuery = JsonDBUtils.getJxQuery(status, userId);

        List<Template> allMatchingTemplates = jsonDBTemplate.find(jxQuery, Template.class);
        int totalItems = allMatchingTemplates.size();
        List<Template> result = allMatchingTemplates.stream()
            .sorted(JsonDBUtils.getComparator().reversed())
            .skip((long) (page - 1) * perPage)
            .limit(perPage)
            .toList();

        return new GalleryResponse<>(result, totalItems);
    }

    public Resource loadTemplateAsResource(Template template) {
        Path dir = Paths.get(templatesPathString, template.getId().toString());
        List<String> possibleExtensions = Arrays.asList("jpg", "jpeg", "png");

        try {
            // Find the first template file that exists
            Path templateFile = possibleExtensions.stream()
                .map(ext -> dir.resolve("template." + ext))
                .filter(Files::exists)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Template not found: " + template.getId()));

            return new UrlResource(templateFile.toUri());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file.", e);
        }
    }

    public void deleteTemplate(Template template) {
        try {
            Path filePath = Paths.get(templatesPathString, template.getId().toString());
            try (Stream<Path> paths = Files.walk(filePath)) {
                if (!paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .allMatch(File::delete)) {
                    throw new RuntimeException("Failed to delete template: " + template.getId());
                }
            }
            jsonDBTemplate.remove(template, Template.class);
            log.info("Template {} deleted", template.getId());
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete template: " + template.getId(), e);
        }
    }

    public Optional<Template> getTemplate(UUID id) {
        return Optional.ofNullable(jsonDBTemplate.findById(id, Template.class));
    }

    public void approveTemplate(Template template) {
        template.setStatus(Status.APPROVED);
        jsonDBTemplate.save(template, Template.class);
        log.info("Template {} approved", template.getId());
    }

    public void rejectTemplate(Template template) {
        template.setStatus(Status.REJECTED);
        jsonDBTemplate.save(template, Template.class);
        log.info("Template {} rejected", template.getId());
    }

    public void resetAllWeights() {
        List<Template> allTemplates = jsonDBTemplate.findAll(Template.class);
        for (Template template : allTemplates) {
            template.setWeight(initialWeight);
        }
        jsonDBTemplate.upsert(allTemplates, Template.class);
        log.info("All templates weights have been reset to {}", initialWeight);
    }
}