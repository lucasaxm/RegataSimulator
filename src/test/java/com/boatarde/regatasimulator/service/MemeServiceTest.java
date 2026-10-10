package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.adapter.media.FileMediaStorage;
import com.boatarde.regatasimulator.application.ImageRenderer;
import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.application.SubmissionOrigin;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.jsondb.*;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemeServiceTest {
    @TempDir Path root;
    private JsonDbSourceRepository sources;
    private JsonDbTemplateRepository templates;
    private JsonDbMemeHistoryRepository history;
    private MediaStorage media;
    private TelegramGateway telegram;
    private ImageRenderer renderer;
    private MemeService service;
    private Path job;
    private final TelegramGateway.Destination destination = new TelegramGateway.Destination(123L, 111, 7);

    @BeforeEach
    void setUp() throws Exception {
        JsonDBTemplate db = new JsonDBTemplate(Files.createDirectories(root.resolve("db")).toString(), "com.boatarde.regatasimulator.models");
        db.createCollection(Source.class); db.createCollection(Template.class); db.createCollection(Meme.class); db.createCollection(Author.class);
        sources = new JsonDbSourceRepository(db); templates = new JsonDbTemplateRepository(db); history = new JsonDbMemeHistoryRepository(db);
        media = new FileMediaStorage(root.resolve("sources").toString(), root.resolve("templates").toString());
        telegram = mock(TelegramGateway.class); renderer = mock(ImageRenderer.class);
        when(renderer.render(any())).thenAnswer(call -> {
            ImageRenderer.Request request = call.getArgument(0);
            assertTrue(Files.exists(request.template()));
            request.sources().forEach(file -> assertTrue(Files.exists(file)));
            job = Files.createTempDirectory(root, "render-");
            return new ImageRenderer.RenderedImage(ImageTestFactory.image(job.resolve("final_output.png")), job);
        });
        when(telegram.sendPhoto(any())).thenAnswer(call -> {
            TelegramGateway.Photo photo = call.getArgument(0);
            assertTrue(Files.exists(photo.file()), "Delivery must retain render ownership");
            return new TelegramGateway.Delivery(123L, 333, origin(333));
        });
        service = service("2026-10-06T12:00:00Z");
    }

    @Test
    void publicationUsesMinimalPoolsEvenWithHistoryAndRecordsDeliveredOrder() throws Exception {
        Source source = source(Status.APPROVED);
        Template template = template(Status.APPROVED, 1);
        history.recordDelivered(Meme.builder().id(UUID.randomUUID()).templateId(template.getId())
            .sourceIds(List.of(source.getId())).message(origin(100)).build());
        service.publish(new MemeService.Publish(MemeService.Origin.ADMIN, destination));
        assertEquals(9, sources.findById(source.getId()).orElseThrow().getWeight());
        assertEquals(9, templates.findById(template.getId()).orElseThrow().getWeight());
        assertEquals(List.of(source.getId()), history.newestFirst().getFirst().getSourceIds());
        assertFalse(Files.exists(job));
        ArgumentCaptor<TelegramGateway.Photo> sent = ArgumentCaptor.forClass(TelegramGateway.Photo.class);
        verify(telegram).sendPhoto(sent.capture());
        assertNull(sent.getValue().buttons()); assertEquals(destination, sent.getValue().destination());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void previewsAreExplicitWithoutProgressMessagesAndNeverPublish(boolean sourceType) throws Exception {
        Source source = source(sourceType ? Status.REVIEW : Status.APPROVED);
        Template template = template(sourceType ? Status.APPROVED : Status.REVIEW, 1);
        UUID id = sourceType ? source.getId() : template.getId();
        var request = new MemeService.Preview(id, destination, null);
        if (sourceType) service.previewSource(request); else service.previewTemplate(request);
        CommonEntity stored = sourceType ? sources.findById(id).orElseThrow() : templates.findById(id).orElseThrow();
        assertEquals(333, stored.getPreviewMessageId()); assertEquals(123L, stored.getPreviewChatId());
        assertEquals(111, stored.getMessage().getMessageId()); assertEquals(Status.REVIEW, stored.getStatus());
        assertEquals(10, stored.getWeight()); assertTrue(history.newestFirst().isEmpty());
        assertFalse(Files.exists(job));
        ArgumentCaptor<TelegramGateway.Photo> sent = ArgumentCaptor.forClass(TelegramGateway.Photo.class);
        verify(telegram).sendPhoto(sent.capture());
        assertEquals(new TelegramGateway.PreviewButtons(id, sourceType ? "source" : "template"), sent.getValue().buttons());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-22", "2026-04-03", "2026-04-14", "2026-04-30", "2026-05-27", "2026-06-10", "2026-08-12", "2026-10-25"})
    void birthdayPoolsFallBackWhenInsufficient(String day) throws Exception {
        source(Status.APPROVED); source(Status.APPROVED); template(Status.APPROVED, 2);
        service(day + "T12:00:00Z").publish(new MemeService.Publish(MemeService.Origin.SCHEDULED, destination));
        assertEquals(2, history.newestFirst().getFirst().getSourceIds().size());
        assertEquals(2, history.newestFirst().getFirst().getSourceIds().stream().distinct().count());
        assertFalse(Files.exists(job));
    }

    @Test
    void deliveryFailureCleansJobWithoutWeightsOrHistory() throws Exception {
        Source source = source(Status.APPROVED);
        Template template = template(Status.APPROVED, 1);
        ApplicationFailure original = new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "controlled failure");
        doThrow(original).when(telegram).sendPhoto(any());
        assertSame(original, assertThrows(ApplicationFailure.class,
            () -> service.publish(new MemeService.Publish(MemeService.Origin.ADMIN, destination))));
        assertEquals(10, sources.findById(source.getId()).orElseThrow().getWeight());
        assertEquals(10, templates.findById(template.getId()).orElseThrow().getWeight());
        assertTrue(history.newestFirst().isEmpty()); assertFalse(Files.exists(job));
    }

    @Test
    void cosmeticProgressFailureCannotChangePreviewModeOrBlockDelivery() throws Exception {
        Source source = source(Status.REVIEW); template(Status.APPROVED, 1);
        doThrow(new IllegalStateException("cosmetic failure")).when(telegram).deleteMessage(123, 222);
        service.previewSource(new MemeService.Preview(source.getId(), destination,
            new TelegramGateway.Delivery(123, 222, null)));
        assertEquals(333, sources.findById(source.getId()).orElseThrow().getPreviewMessageId());
        assertTrue(history.newestFirst().isEmpty()); assertFalse(Files.exists(job));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void moderationDuringDeliveryCannotBeOverwrittenByPreview(boolean sourceType) throws Exception {
        Source source = source(sourceType ? Status.REVIEW : Status.APPROVED);
        Template template = template(sourceType ? Status.APPROVED : Status.REVIEW, 1);
        UUID id = sourceType ? source.getId() : template.getId();
        doAnswer(call -> {
            if (sourceType) sources.decideReview(id, Status.APPROVED); else templates.decideReview(id, Status.APPROVED);
            return new TelegramGateway.Delivery(123, 333, origin(333));
        }).when(telegram).sendPhoto(any());
        assertThrows(ApplicationFailure.class, () -> {
            if (sourceType) service.previewSource(new MemeService.Preview(id, destination, null));
            else service.previewTemplate(new MemeService.Preview(id, destination, null));
        });
        CommonEntity stored = sourceType ? sources.findById(id).orElseThrow() : templates.findById(id).orElseThrow();
        assertEquals(Status.APPROVED, stored.getStatus()); assertNull(stored.getPreviewMessageId());
        assertEquals(111, stored.getMessage().getMessageId()); assertFalse(Files.exists(job));
    }

    @Test
    void unusableDeliveryIdentityLeavesPreviewUnboundAndCleansOutput() throws Exception {
        Source source = source(Status.REVIEW); template(Status.APPROVED, 1);
        doReturn(new TelegramGateway.Delivery(999, 333, null)).when(telegram).sendPhoto(any());
        assertThrows(ApplicationFailure.class, () -> service.previewSource(new MemeService.Preview(source.getId(), destination, null)));
        assertNull(sources.findById(source.getId()).orElseThrow().getPreviewChatId()); assertFalse(Files.exists(job));
    }

    @Test
    void insufficientSourcesFailBeforeRendererOrDelivery() throws Exception {
        source(Status.APPROVED); template(Status.APPROVED, 2);
        ApplicationFailure failure = assertThrows(ApplicationFailure.class,
            () -> service.publish(new MemeService.Publish(MemeService.Origin.ADMIN, destination)));
        assertEquals(ApplicationFailure.Kind.UNAVAILABLE, failure.getKind());
        verifyNoInteractions(renderer, telegram);
    }

    private MemeService service(String instant) {
        return new MemeService(sources, templates, history, media, renderer, telegram,
            Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void typedSubmissionPersistsAuthorOriginalUploadAndBoundPreview(boolean sourceType) throws Exception {
        if (sourceType) template(Status.APPROVED, 1); else source(Status.APPROVED);
        when(telegram.download(eq("fixture-file"), any(), anyString())).thenAnswer(call ->
            ImageTestFactory.image(((Path) call.getArgument(1)).resolve((String) call.getArgument(2))));
        when(telegram.sendText(any())).thenReturn(new TelegramGateway.Delivery(123, 222, origin(222)));
        var submitter = Author.builder().id(42L).firstName("Fixture author").build();
        var upload = new SubmissionService.Upload("fixture-file", "upload.png", submitter,
            new SubmissionOrigin(destination, origin(111)));
        SubmissionService submissions = new SubmissionService(sources, templates, new JsonDbAuthorRepository(
            new JsonDBTemplate(root.resolve("db").toString(), "com.boatarde.regatasimulator.models")),
            media, telegram, service, 10, 10);
        SubmissionService.Result result = sourceType ? submissions.submitSource(new SubmissionService.SourceSubmission("submitted", upload))
            : submissions.submitTemplate(new SubmissionService.TemplateSubmission(List.of(TemplateArea.builder().index(1).source(1)
                .topLeft(new AreaCorner(0, 0)).topRight(new AreaCorner(1, 0)).bottomRight(new AreaCorner(1, 1))
                .bottomLeft(new AreaCorner(0, 1)).build()), upload));
        assertEquals(SubmissionService.Outcome.PREVIEWED, result.outcome());
        CommonEntity stored = sourceType ? sources.findById(result.id()).orElseThrow() : templates.findById(result.id()).orElseThrow();
        assertEquals(Status.REVIEW, stored.getStatus()); assertEquals(111, stored.getMessage().getMessageId());
        assertEquals(42L, stored.getMessage().getFrom().getId()); assertEquals(333, stored.getPreviewMessageId());
        assertTrue(history.newestFirst().isEmpty()); assertFalse(Files.exists(job));
        assertTrue(Files.exists(media.image(sourceType ? MediaStorage.Kind.SOURCE : MediaStorage.Kind.TEMPLATE, result.id())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidUploadedImageIsCleanedBeforeAnyMetadataOrPreview(boolean sourceType) throws Exception {
        when(telegram.download(eq("fixture-file"), any(), anyString())).thenAnswer(call ->
            Files.writeString(((Path) call.getArgument(1)).resolve((String) call.getArgument(2)), "not an image"));
        JsonDBTemplate db = new JsonDBTemplate(root.resolve("db").toString(), "com.boatarde.regatasimulator.models");
        var submissions = new SubmissionService(sources, templates, new JsonDbAuthorRepository(db), media, telegram, service, 10, 10);
        var upload = new SubmissionService.Upload("fixture-file", "upload.png", Author.builder().id(42L).build(),
            new SubmissionOrigin(destination, origin(111)));
        assertThrows(ApplicationFailure.class, () -> {
            if (sourceType) submissions.submitSource(new SubmissionService.SourceSubmission("submitted", upload));
            else submissions.submitTemplate(new SubmissionService.TemplateSubmission(List.of(TemplateArea.builder().index(1).source(1)
                .topLeft(new AreaCorner(0, 0)).topRight(new AreaCorner(1, 0)).bottomRight(new AreaCorner(1, 1))
                .bottomLeft(new AreaCorner(0, 1)).build()), upload));
        });
        assertTrue(db.findAll(Author.class).isEmpty());
        assertTrue(sources.find(com.boatarde.regatasimulator.repository.SourceRepository.Criteria.all()).isEmpty());
        assertTrue(templates.find(com.boatarde.regatasimulator.repository.TemplateRepository.Criteria.all()).isEmpty());
        try (var files = Files.list(root.resolve(sourceType ? "sources" : "templates"))) { assertEquals(0, files.count()); }
        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realModerationCommitsBeforeNotificationAndPreservesDecisionOnFailure(boolean approved) throws Exception {
        Source source = source(Status.REVIEW);
        SourceService sourceService = new SourceService(sources);
        TemplateService templateService = new TemplateService(root.resolve("templates").toString(), templates);
        var moderation = new ModerationService(sourceService, templateService, telegram);
        doAnswer(call -> {
            assertEquals(approved ? Status.APPROVED : Status.REJECTED, sources.findById(source.getId()).orElseThrow().getStatus());
            throw new IllegalStateException("controlled notification failure");
        }).when(telegram).sendText(any());
        var decision = new ModerationService.Decision(ModerationService.ItemType.SOURCE, source.getId(),
            approved ? Status.APPROVED : Status.REJECTED, "reason", new ModerationService.Actor("test-admin"));
        assertEquals(ModerationService.Notification.FAILED, moderation.decide(decision).notification());
        assertThrows(ApplicationFailure.class, () -> moderation.decide(decision));
        assertEquals(10, sources.findById(source.getId()).orElseThrow().getWeight());
    }

    private Source source(Status status) throws Exception {
        Source source = new Source(); source.setId(UUID.randomUUID()); source.setStatus(status); source.setWeight(10);
        source.setDescription("ordinary fixture"); source.setMessage(origin(111)); sources.insertSubmission(source);
        ImageTestFactory.image(media.prepare(MediaStorage.Kind.SOURCE, source.getId()).resolve("source.png")); return source;
    }

    private Template template(Status status, int slots) throws Exception {
        Template template = new Template(); template.setId(UUID.randomUUID()); template.setStatus(status); template.setWeight(10);
        template.setMessage(origin(111));
        template.setAreas(IntStream.rangeClosed(1, slots).mapToObj(i -> TemplateArea.builder().index(i).source(i)
            .topLeft(new AreaCorner(0, 0)).topRight(new AreaCorner(1, 0)).bottomRight(new AreaCorner(1, 1))
            .bottomLeft(new AreaCorner(0, 1)).background(false).build()).toList());
        templates.insertSubmission(template);
        ImageTestFactory.image(media.prepare(MediaStorage.Kind.TEMPLATE, template.getId()).resolve("template.png")); return template;
    }

    private Message origin(int id) {
        Message message = new Message(); message.setMessageId(id); message.setDate(id);
        Chat chat = new Chat(); chat.setId(123L); message.setChat(chat);
        User user = new User(); user.setId(42L); message.setFrom(user); return message;
    }
}