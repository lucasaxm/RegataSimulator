package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.application.SubmissionOrigin;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.util.MediaValidation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@Slf4j
public class SubmissionService {
    public record Upload(String fileId, String fileName, Author author, SubmissionOrigin origin) { }
    public record SourceSubmission(String description, Upload upload) { }
    public record TemplateSubmission(List<TemplateArea> areas, Upload upload) {
        public TemplateSubmission { areas = List.copyOf(areas); }
    }
    public enum Outcome { PREVIEWED, DUPLICATE, INVALID_DESCRIPTION }
    public record Result(UUID id, Outcome outcome) { }
    private final SourceRepository sources;
    private final TemplateRepository templates;
    private final AuthorRepository authors;
    private final MediaStorage media;
    private final TelegramGateway telegram;
    private final MemeService memes;
    private final int sourceWeight;
    private final int templateWeight;

    public SubmissionService(SourceRepository sources, TemplateRepository templates, AuthorRepository authors,
                             MediaStorage media, TelegramGateway telegram, MemeService memes,
                             @Value("${regata-simulator.sources.initial-weight}") int sourceWeight,
                             @Value("${regata-simulator.templates.initial-weight}") int templateWeight) {
        this.sources = sources; this.templates = templates; this.authors = authors; this.media = media;
        this.telegram = telegram; this.memes = memes; this.sourceWeight = sourceWeight; this.templateWeight = templateWeight;
    }

    public Result submitSource(SourceSubmission request) {
        var destination = request.upload().origin().destination();
        String description = request.description() == null ? "" : request.description().strip();
        if (description.isEmpty()) {
            telegram.sendText(new TelegramGateway.Text(destination, "Erro: A descrição não pode estar vazia.", false));
            return new Result(null, Outcome.INVALID_DESCRIPTION);
        }
        var duplicate = sources.find(SourceRepository.Criteria.all()).stream().filter(source -> source.getDescription() != null
            && source.getDescription().toLowerCase(Locale.ROOT).equals(description.toLowerCase(Locale.ROOT))).findFirst();
        if (duplicate.isPresent()) {
            Source source = duplicate.get();
            telegram.sendPhoto(new TelegramGateway.Photo(destination, media.image(MediaStorage.Kind.SOURCE, source.getId()),
                "Erro: Já existe uma source com esta descrição.%nSource existente ID: %s%nDescrição: %s"
                    .formatted(source.getId(), source.getDescription()), null));
            return new Result(source.getId(), Outcome.DUPLICATE);
        }
        UUID id = UUID.randomUUID();
        boolean committed = false;
        try {
            prepare(MediaStorage.Kind.SOURCE, id, request.upload(), null);
            Source source = new Source(); source.setId(id); source.setStatus(Status.REVIEW); source.setWeight(sourceWeight);
            source.setDescription(description); source.setMessage(request.upload().origin().legacyMessage());
            var progress = telegram.sendText(new TelegramGateway.Text(destination, "Criando source...", false));
            authors.recordSubmitter(request.upload().author());
            sources.insertSubmission(source);
            committed = true;
            memes.previewSource(new MemeService.Preview(id, destination, progress));
            return new Result(id, Outcome.PREVIEWED);
        } catch (RuntimeException e) {
            if (!committed && sources.findById(id).isEmpty()) cleanup(MediaStorage.Kind.SOURCE, id);
            throw e;
        }
    }

    public Result submitTemplate(TemplateSubmission request) {
        UUID id = UUID.randomUUID();
        boolean committed = false;
        try {
            prepare(MediaStorage.Kind.TEMPLATE, id, request.upload(), request.areas());
            Template template = new Template(); template.setId(id); template.setStatus(Status.REVIEW); template.setWeight(templateWeight);
            template.setAreas(request.areas()); template.setMessage(request.upload().origin().legacyMessage());
            var destination = request.upload().origin().destination();
            var progress = telegram.sendText(new TelegramGateway.Text(destination, "Criando template...", false));
            authors.recordSubmitter(request.upload().author());
            templates.insertSubmission(template);
            committed = true;
            memes.previewTemplate(new MemeService.Preview(id, destination, progress));
            return new Result(id, Outcome.PREVIEWED);
        } catch (RuntimeException e) {
            if (!committed && templates.findById(id).isEmpty()) cleanup(MediaStorage.Kind.TEMPLATE, id);
            throw e;
        }
    }

    private void prepare(MediaStorage.Kind kind, UUID id, Upload upload, List<TemplateArea> areas) {
        try {
            if (areas != null) MediaValidation.geometry(areas, null);
            String fileName = (kind == MediaStorage.Kind.SOURCE ? "source" : "template") + MediaValidation.extension(upload.fileName());
            Path directory = media.prepare(kind, id);
            Path image = telegram.download(upload.fileId(), directory, fileName);
            if (image == null || !image.normalize().getParent().equals(directory.normalize())) {
                throw new IOException("Downloader returned no owned image");
            }
            var dimensions = MediaValidation.image(image);
            if (areas != null) MediaValidation.geometry(areas, dimensions);
        } catch (IOException e) {
            throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Invalid uploaded media", e);
        }
    }

    private void cleanup(MediaStorage.Kind kind, UUID id) {
        try { media.delete(kind, id); }
        catch (RuntimeException e) { log.warn("Uncommitted submission media requires cleanup"); }
    }
}