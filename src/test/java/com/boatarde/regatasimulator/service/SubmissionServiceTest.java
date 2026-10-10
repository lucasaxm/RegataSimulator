package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.adapter.media.FileMediaStorage;
import com.boatarde.regatasimulator.application.*;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubmissionServiceTest {
    @TempDir Path root;
    SourceRepository sources;
    TemplateRepository templates;
    AuthorRepository authors;
    TelegramGateway telegram;
    MemeService memes;
    MediaStorage media;
    SubmissionService service;
    Path downloaded;

    @BeforeEach void setUp() {
        sources = mock(SourceRepository.class); templates = mock(TemplateRepository.class);
        authors = mock(AuthorRepository.class); telegram = mock(TelegramGateway.class); memes = mock(MemeService.class);
        media = new FileMediaStorage(root.resolve("sources").toString(), root.resolve("templates").toString());
        service = new SubmissionService(sources, templates, authors, media, telegram, memes, 10, 10);
        when(telegram.download(anyString(), any(), anyString())).thenAnswer(call -> {
            downloaded = ((Path) call.getArgument(1)).resolve((String) call.getArgument(2));
            return ImageTestFactory.image(downloaded);
        });
    }

    static Stream<Arguments> failures() {
        return Stream.of(true, false).flatMap(source -> Stream.of("download", "partial", "invalidImage", "progress", "author", "insert", "preview")
            .map(mode -> Arguments.of(source, mode)));
    }

    @ParameterizedTest @MethodSource("failures")
    void failuresCompensateOnlyUncommittedMedia(boolean sourceType, String mode) throws Exception {
        RuntimeException failure = new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "fixture failure");
        stubFailure(sourceType, mode, failure);
        assertThrows(RuntimeException.class, () -> submit(sourceType));
        assertEquals(mode.equals("preview"), Files.exists(downloaded.getParent()));
        if (!mode.equals("preview")) verifyNoInteractions(memes);
        if (List.of("download", "partial", "invalidImage", "progress", "author").contains(mode)) {
            verify(sources, never()).insertSubmission(any()); verify(templates, never()).insertSubmission(any());
        }
    }

    private void stubFailure(boolean sourceType, String mode, RuntimeException failure) {
        if (List.of("download", "partial", "invalidImage").contains(mode)) {
            doAnswer(call -> {
                downloaded = ((Path) call.getArgument(1)).resolve((String) call.getArgument(2));
                if (!mode.equals("download")) Files.writeString(downloaded, "invalid or partial bytes");
                if (!mode.equals("invalidImage")) throw failure;
                return downloaded;
            }).when(telegram).download(anyString(), any(), anyString());
        }
        if (mode.equals("progress")) doThrow(failure).when(telegram).sendText(any());
        if (mode.equals("author")) doThrow(failure).when(authors).recordSubmitter(any());
        stubPersistenceOrPreviewFailure(sourceType, mode, failure);
    }

    private void stubPersistenceOrPreviewFailure(boolean sourceType, String mode, RuntimeException failure) {
        if (mode.equals("insert")) {
            if (sourceType) doThrow(failure).when(sources).insertSubmission(any());
            else doThrow(failure).when(templates).insertSubmission(any());
        }
        if (mode.equals("preview")) {
            if (sourceType) doThrow(failure).when(memes).previewSource(any());
            else doThrow(failure).when(memes).previewTemplate(any());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void typedSubmissionPreservesAuthorMediaAndProgressIdentity(boolean sourceType) throws Exception {
        var progress = new TelegramGateway.Delivery(123, 222, null);
        when(telegram.sendText(any())).thenReturn(progress);
        submit(sourceType);
        var order = inOrder(telegram, authors, sources, templates, memes);
        order.verify(telegram).download(eq("fixture"), any(), eq(sourceType ? "source.jpeg" : "template.jpeg"));
        order.verify(telegram).sendText(new TelegramGateway.Text(TelegramGateway.Destination.chat(123),
            sourceType ? "Criando source..." : "Criando template...", false));
        order.verify(authors).recordSubmitter(argThat(author -> author.getId() == 42L
            && "fixture".equals(author.getUserName()) && "Ana".equals(author.getFirstName()) && "Silva".equals(author.getLastName())));
        if (sourceType) {
            var captured = org.mockito.ArgumentCaptor.forClass(Source.class);
            order.verify(sources).insertSubmission(captured.capture());
            assertEquals("Barco: azul", captured.getValue().getDescription());
            assertEquals(Status.REVIEW, captured.getValue().getStatus()); assertEquals(10, captured.getValue().getWeight());
            order.verify(memes).previewSource(new MemeService.Preview(captured.getValue().getId(), TelegramGateway.Destination.chat(123), progress));
        } else {
            var captured = org.mockito.ArgumentCaptor.forClass(Template.class);
            order.verify(templates).insertSubmission(captured.capture());
            assertEquals(areas(), captured.getValue().getAreas());
            assertEquals(Status.REVIEW, captured.getValue().getStatus()); assertEquals(10, captured.getValue().getWeight());
            order.verify(memes).previewTemplate(new MemeService.Preview(captured.getValue().getId(), TelegramGateway.Destination.chat(123), progress));
        }
        assertTrue(Files.exists(downloaded));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"   "})
    void emptyDescriptionsRejectBeforeDownloadOrPersistence(String description) {
        assertEquals(SubmissionService.Outcome.INVALID_DESCRIPTION,
            service.submitSource(new SubmissionService.SourceSubmission(description, upload())).outcome());
        verify(telegram).sendText(new TelegramGateway.Text(TelegramGateway.Destination.chat(123), "Erro: A descrição não pode estar vazia.", false));
        verify(telegram, never()).download(anyString(), any(), anyString());
        verifyNoInteractions(authors, templates, memes);
    }

    @Test void duplicateUsesCaseInsensitiveDescriptionAndExistingMedia() throws Exception {
        Source duplicate = new Source(); duplicate.setId(UUID.randomUUID()); duplicate.setDescription("BARCO: AZUL");
        Source unnamed = new Source(); unnamed.setId(UUID.randomUUID());
        when(sources.find(SourceRepository.Criteria.all())).thenReturn(List.of(unnamed, duplicate));
        ImageTestFactory.image(media.prepare(MediaStorage.Kind.SOURCE, duplicate.getId()).resolve("source.png"));
        assertEquals(SubmissionService.Outcome.DUPLICATE, submit(true).outcome());
        verify(telegram).sendPhoto(argThat(photo -> photo.caption().contains(duplicate.getId().toString()) && photo.caption().contains("BARCO: AZUL")));
        verify(telegram, never()).download(anyString(), any(), anyString()); verifyNoInteractions(authors, memes);
        media.delete(MediaStorage.Kind.SOURCE, duplicate.getId());
        assertThrows(ApplicationFailure.class, () -> submit(true));
        verify(sources, never()).insertSubmission(any());
    }

    @Test void outOfBoundsTemplateFailsBeforeMetadataAndCleansDownload() {
        var invalid = List.of(TemplateArea.builder().index(1).source(1).topLeft(new AreaCorner(0, 0))
            .topRight(new AreaCorner(500, 0)).bottomRight(new AreaCorner(500, 500)).bottomLeft(new AreaCorner(0, 500)).build());
        assertThrows(ApplicationFailure.class, () -> service.submitTemplate(new SubmissionService.TemplateSubmission(invalid, upload())));
        assertFalse(Files.exists(downloaded.getParent())); verifyNoInteractions(authors, memes);
        verify(templates, never()).insertSubmission(any());
    }

    private SubmissionService.Result submit(boolean source) {
        return source ? service.submitSource(new SubmissionService.SourceSubmission("  Barco: azul  ", upload()))
            : service.submitTemplate(new SubmissionService.TemplateSubmission(areas(), upload()));
    }
    private SubmissionService.Upload upload() {
        return new SubmissionService.Upload("fixture", "Original.JPEG", Author.builder().id(42L).userName("fixture")
            .firstName("Ana").lastName("Silva").build(), new SubmissionOrigin(TelegramGateway.Destination.chat(123), null));
    }
    private List<TemplateArea> areas() {
        return List.of(TemplateArea.builder().index(1).source(1).topLeft(new AreaCorner(0, 1)).topRight(new AreaCorner(20, 1))
            .bottomRight(new AreaCorner(20, 30)).bottomLeft(new AreaCorner(0, 30)).background(true).build());
    }
}