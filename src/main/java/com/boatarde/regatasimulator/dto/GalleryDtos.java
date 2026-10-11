package com.boatarde.regatasimulator.dto;

import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.service.SourceImporterService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.List;
import java.util.UUID;

/** HTTP-only projections; persisted Telegram messages and preview bindings never leave the server. */
public final class GalleryDtos {
    private GalleryDtos() { }

    public record Author(Long id, @JsonProperty("first_name") String firstName,
                         @JsonProperty("last_name") String lastName, String username) { }
    public record Origin(Integer date, Author from) { }
    public record SourceItem(UUID id, int weight, Status status, String description, Origin message) { }
    public record TemplateItem(UUID id, int weight, Status status, List<TemplateArea> areas, Origin message) { }
    public record ImportReport(List<SourceItem> created, List<SourceImporterService.RowResult> rows,
                               boolean persistenceFailed) { }

    public static SourceItem source(Source source) {
        return new SourceItem(source.getId(), source.getWeight(), source.getStatus(), source.getDescription(),
            origin(source.getMessage()));
    }

    public static TemplateItem template(Template template) {
        return new TemplateItem(template.getId(), template.getWeight(), template.getStatus(), template.getAreas(),
            origin(template.getMessage()));
    }

    public static GalleryResponse<SourceItem> sources(GalleryResponse<Source> gallery) {
        return new GalleryResponse<>(gallery.getItems().stream().map(GalleryDtos::source).toList(), gallery.getTotalItems());
    }

    public static GalleryResponse<TemplateItem> templates(GalleryResponse<Template> gallery) {
        return new GalleryResponse<>(gallery.getItems().stream().map(GalleryDtos::template).toList(), gallery.getTotalItems());
    }

    public static ImportReport report(SourceImporterService.ImportReport report) {
        return new ImportReport(report.created().stream().map(GalleryDtos::source).toList(), report.rows(), report.persistenceFailed());
    }

    private static Origin origin(Message message) {
        if (message == null || message.getFrom() == null) {
            return null;
        }
        var from = message.getFrom();
        return new Origin(message.getDate(), new Author(from.getId(), from.getFirstName(), from.getLastName(), from.getUserName()));
    }
}