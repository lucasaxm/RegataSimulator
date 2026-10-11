package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.ImageRenderer;
import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import com.boatarde.regatasimulator.util.MediaValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class MemeService {
    public enum Origin { TELEGRAM_COMMAND, ADMIN, SCHEDULED }
    public record Publish(Origin origin, TelegramGateway.Destination destination) { }
    public record Preview(UUID id, TelegramGateway.Destination destination, TelegramGateway.Delivery progressMessage) { }
    private final SourceRepository sources;
    private final TemplateRepository templates;
    private final MemeHistoryRepository history;
    private final MediaStorage media;
    private final ImageRenderer renderer;
    private final TelegramGateway telegram;
    private final Clock clock;
    private final MetadataUnitOfWork metadata;

    public MemeService(SourceRepository sources, TemplateRepository templates, MemeHistoryRepository history,
                       MediaStorage media, ImageRenderer renderer, TelegramGateway telegram, Clock clock) {
        this(sources,templates,history,media,renderer,telegram,clock,Runnable::run);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MemeService(SourceRepository sources, TemplateRepository templates, MemeHistoryRepository history,
                       MediaStorage media, ImageRenderer renderer, TelegramGateway telegram, Clock clock, MetadataUnitOfWork metadata) {
        this.sources = sources; this.templates = templates; this.history = history; this.media = media;
        this.renderer = renderer; this.telegram = telegram; this.clock = clock;
        this.metadata = metadata;
    }

    public TelegramGateway.Delivery publish(Publish request) {
        java.util.Objects.requireNonNull(request.origin(), "Publication origin is required");
        List<Meme> recent = history.newestFirst();
        Template template = selectTemplate(recent, false);
        List<Source> selected = selectSources(template, recent);
        try (var image = render(template, selected)) {
            var delivered = telegram.sendPhoto(new TelegramGateway.Photo(request.destination(), image.file(), null, null));
            metadata.execute(() -> {
                selected.forEach(source -> sources.decreaseWeight(source.getId()));
                templates.decreaseWeight(template.getId());
                history.recordDelivered(Meme.builder().id(UUID.randomUUID()).templateId(template.getId())
                    .sourceIds(selected.stream().map(Source::getId).toList()).message(delivered.legacyMessage()).build());
            });
            return delivered;
        }
    }

    public TelegramGateway.Delivery previewSource(Preview request) {
        Source source = sources.findById(request.id()).orElseThrow(() -> missing("Source"));
        Template template = selectTemplate(List.of(), true);
        return preview(request, template, List.of(source), "source");
    }

    public TelegramGateway.Delivery previewTemplate(Preview request) {
        Template template = templates.findById(request.id()).orElseThrow(() -> missing("Template"));
        return preview(request, template, selectSources(template, history.newestFirst()), "template");
    }

    private TelegramGateway.Delivery preview(Preview request, Template template, List<Source> selected, String type) {
        try (var image = render(template, selected)) {
            removeProgress(request.progressMessage());
            var response = telegram.sendPhoto(new TelegramGateway.Photo(request.destination(), image.file(), null,
                new TelegramGateway.PreviewButtons(request.id(), type)));
            if (response == null || response.chatId() != request.destination().chatId()
                || response.messageId() == null || response.messageId() <= 0) {
                throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Preview returned no usable identity");
            }
            boolean bound = type.equals("source") ? sources.bindReviewPreview(request.id(), response.chatId(), response.messageId())
                : templates.bindReviewPreview(request.id(), response.chatId(), response.messageId());
            if (!bound) throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Preview item no longer in review");
            return response;
        }
    }

    private void removeProgress(TelegramGateway.Delivery message) {
        if (message == null || message.messageId() == null) return;
        try { telegram.deleteMessage(message.chatId(), message.messageId()); }
        catch (RuntimeException e) { log.warn("Could not delete preview progress message; continuing preview delivery"); }
    }

    private ImageRenderer.RenderedImage render(Template template, List<Source> selected) {
        try { MediaValidation.geometry(template.getAreas(), null); }
        catch (IOException e) { throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Invalid template geometry", e); }
        return renderer.render(new ImageRenderer.Request(image(MediaStorage.Kind.TEMPLATE, template.getId()),
            template.getAreas(), selected.stream().map(source -> image(MediaStorage.Kind.SOURCE, source.getId())).toList(), null));
    }

    private java.nio.file.Path image(MediaStorage.Kind kind, UUID id) {
        try { return media.image(kind, id); }
        catch (ApplicationFailure e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "Selected media unavailable", e);
        }
    }

    private Template selectTemplate(List<Meme> recent, boolean singleArea) {
        var candidates = new ArrayList<>(templates.find(new TemplateRepository.Criteria(Status.APPROVED, null, singleArea)));
        if (candidates.isEmpty()) throw unavailable("No approved templates available");
        if (singleArea) return JsonDBUtils.selectRandomSingleAreaTemplate(candidates);
        JsonDBUtils.excludeRecent(candidates, recent.stream().map(Meme::getTemplateId), 1);
        return JsonDBUtils.selectTemplatesWithWeight(candidates, 1).getFirst();
    }

    private List<Source> selectSources(Template template, List<Meme> recent) {
        try { MediaValidation.geometry(template.getAreas(), null); }
        catch (IOException e) { throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Invalid template geometry", e); }
        int required = (int) template.getAreas().stream().map(TemplateArea::getSource).distinct().count();
        List<String> birthday = birthdayNames();
        var candidates = new ArrayList<>(sources.find(new SourceRepository.Criteria(Status.APPROVED, null, birthday)));
        if (candidates.size() < required && !birthday.isEmpty()) {
            candidates = new ArrayList<>(sources.find(new SourceRepository.Criteria(Status.APPROVED, null, List.of())));
        }
        if (required == 0 || candidates.size() < required) throw unavailable("Insufficient approved sources");
        JsonDBUtils.excludeRecent(candidates, recent.stream().filter(meme -> meme.getSourceIds() != null)
            .flatMap(meme -> meme.getSourceIds().stream()), required);
        return JsonDBUtils.selectSourcesWithWeight(candidates, required);
    }

    private List<String> birthdayNames() {
        LocalDate date = LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
        return switch (date.getMonthValue() * 100 + date.getDayOfMonth()) {
            case 122 -> List.of("brenda");
            case 403 -> List.of("ander");
            case 414 -> List.of("gab");
            case 430 -> List.of("gui");
            case 527 -> List.of("xxk", "gayzito");
            case 610 -> List.of("lucas", "c4", "celta");
            case 812 -> List.of("valb", "punhet");
            case 1025 -> List.of("dedey");
            default -> List.of();
        };
    }

    private ApplicationFailure unavailable(String detail) { return new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, detail); }
    private ApplicationFailure missing(String detail) { return new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, detail); }
}