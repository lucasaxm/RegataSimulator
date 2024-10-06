package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.ReviewTemplateBody;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class TemplateService {

    private final String templatesPathString;

    private final String templatesReviewPathString;

    private final RegataSimulatorBot bot;

    private final RouterService routerService;

    public TemplateService(@Value("${regata-simulator.templates.path}") String templatesPathString,
                           @Value("${regata-simulator.templates.review-path}") String templatesReviewPathString,
                           RegataSimulatorBot bot,
                           RouterService routerService) {
        this.templatesPathString = templatesPathString;
        this.templatesReviewPathString = templatesReviewPathString;
        this.bot = bot;
        this.routerService = routerService;
    }

    public GalleryResponse<Template> getTemplates(int page, int perPage, boolean review) {
        Path dir = Paths.get(review ? templatesReviewPathString : templatesPathString);
        try {
            List<Template> allItems = Files.list(dir)
                .filter(Files::isDirectory)
                .flatMap(subdir -> {
                    try {
                        return Files.list(subdir)
                            .filter(file -> file.getFileName().toString().equalsIgnoreCase("template.jpg") ||
                                file.getFileName().toString().equalsIgnoreCase("template.png"))
                            .map(file -> {
                                String details = getTemplateDetails(subdir.getFileName().toString(), review);
                                Update update = getTemplateUpdate(subdir.getFileName().toString(), review);
                                try {
                                    Template response = Template.builder()
                                        .id(UUID.fromString(subdir.getFileName().toString()))
                                        .areas(TelegramUtils.parseTemplateCsv(details))
                                        .build();
                                    if (update != null) {
                                        response.setAuthor(new Author(update.getMessage().getFrom()));
                                        response.setCreatedAt(LocalDateTime.ofInstant(
                                            Instant.ofEpochSecond(update.getMessage().getDate()),
                                            ZoneId.systemDefault()));
                                    }
                                    return response;
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

            List<Template> pageItems = allItems.subList(startIndex, endIndex);

            return new GalleryResponse<>(pageItems, totalItems);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read template items", e);
        }
    }

    public String getTemplateDetails(String id, boolean review) {
        Path csvPath = Paths.get(review ? templatesReviewPathString : templatesPathString, id, "template.csv");
        try {
            return new String(Files.readAllBytes(csvPath));
        } catch (IOException e) {
            return null;
        }
    }

    private Update getTemplateUpdate(String id, boolean review) {
        Path filePath = Paths.get(review ? templatesReviewPathString : templatesPathString, id, "update.json");
        try {
            String updateJson = Files.readString(filePath);
            return TelegramUtils.fromJson(updateJson);
        } catch (IOException e) {
            log.error("Failed to read update.json for template {}{}", id, review ? " [review]" : "", e);
            return null;
        }
    }

    public Resource loadTemplateAsResource(UUID id, boolean review) {
        Path dir = Paths.get(review ? templatesReviewPathString : templatesPathString, id.toString());

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

    public void deleteTemplate(UUID id, boolean review) {
        try {
            Path filePath = Paths.get(review ? templatesReviewPathString : templatesPathString, id.toString());
            try (Stream<Path> paths = Files.walk(filePath)) {
                paths.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete template: " + id, e);
        }
    }


    public void reviewTemplate(UUID id, ReviewTemplateBody reviewTemplateBody) {
        Update update = getTemplateUpdate(id.toString(), true);
        if (reviewTemplateBody.isApproved()) {
            approveTemplate(id);
            if (update != null) {
                routerService.startFlow(update, bot, WorkflowAction.APPROVE_TEMPLATE_STEP);
            }
        } else {
            deleteTemplate(id, true);

            if (update != null) {
                Message message = new Message();
                message.setText(reviewTemplateBody.getReason());
                update.setChannelPost(message);
            }

            routerService.startFlow(update, bot, WorkflowAction.REFUSE_TEMPLATE_STEP);
        }
    }

    private void approveTemplate(UUID id) {
        Path source = Paths.get(templatesReviewPathString, id.toString());
        Path target = Paths.get(templatesPathString, id.toString());

        try {
            Files.move(source, target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to move template from review to production: " + id, e);
        }
    }
}