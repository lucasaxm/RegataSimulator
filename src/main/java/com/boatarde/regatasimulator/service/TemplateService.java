package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Slf4j
public class TemplateService {

    private final String templatesPathString;
    private final JsonDBTemplate jsonDBTemplate;

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

        try {
            Path templateFile = dir.resolve("template.jpg");
            if (!templateFile.toFile().exists()) {
                templateFile = dir.resolve("template.png");
            }
            if (!templateFile.toFile().exists()) {
                throw new RuntimeException("Template not found: " + template.getId());
            }
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

    // temporary method to save templates from file system to jsondb
    public List<Template> saveTemplates() {
        Path templatesPath = Paths.get(templatesPathString);
        List<Template> templates = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(templatesPath)) {
            paths.filter(Files::isDirectory)
                .filter(path -> {
                    try {
                        UUID.fromString(path.getFileName().toString());
                        return true;
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid template id: %s".formatted(path));
                        return false;
                    }
                })
                .forEach(path -> {
                    Template template = new Template();
                    template.setId(UUID.fromString(path.getFileName().toString()));
                    template.setWeight(30);
                    template.setStatus(Status.APPROVED);
                    try {
                        String jsonStr = Files.readString(path.resolve("message.json"));
                        Message message = TelegramUtils.fromJson(jsonStr, Message.class);
                        template.setMessage(message);
                    } catch (IOException e) {
                        log.error("Failed to read message json: %s".formatted(path));
                    }
                    try {
                        List<TemplateArea> areas =
                            JsonDBUtils.parseTemplateCsv(Files.readString(path.resolve("template.csv")));
                        template.setAreas(areas);
                    } catch (IOException e) {
                        log.error("Failed to read areas csv: %s".formatted(path));
                    }
                    templates.add(template);
                });
        } catch (IOException e) {
            log.error("Failed to read templates", e);
        }
        jsonDBTemplate.upsert(templates, Template.class);
        Map<Long, Author> authors = new HashMap<>();
        templates.stream()
            .filter(template -> template.getMessage() != null)
            .map(template -> template.getMessage().getFrom())
            .forEach(user -> {
                Author author = new Author(user);
                authors.put(author.getId(), author);
            });

        jsonDBTemplate.upsert(new ArrayList<>(authors.values()), Author.class);

        return templates;
    }
}