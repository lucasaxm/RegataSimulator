package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.adapter.telegram.TelegramRouter;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.jsondb.*;
import com.boatarde.regatasimulator.util.FileUtils;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectOperationsTest {
    @TempDir Path root;
    private JsonDBTemplate db;
    private TelegramGateway telegram;
    private ReportService reports;
    private BackupService backups;

    @BeforeEach
    void setUp() throws Exception {
        Path database = Files.createDirectories(root.resolve("db"));
        Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("sources"));
        db = new JsonDBTemplate(database.toString(), "com.boatarde.regatasimulator.models");
        db.createCollection(Source.class); db.createCollection(Template.class); db.createCollection(Author.class);
        telegram = mock(TelegramGateway.class);
        reports = new ReportService(new JsonDbSourceRepository(db), new JsonDbTemplateRepository(db),
            new JsonDbAuthorRepository(db), telegram);
        backups = new BackupService(456L, telegram, reports, database.toString(),
            root.resolve("templates").toString(), root.resolve("sources").toString());
    }

    @Test
    void pingHasExplicitDestinationAndDeterministicClock() {
        var destination = new TelegramGateway.Destination(123L, 42, 7);
        new PingService(telegram, Clock.fixed(Instant.ofEpochSecond(200), ZoneOffset.UTC)).pong(destination, 198);
        verify(telegram).sendText(new TelegramGateway.Text(destination, "pong! (2s)", false));
    }

    @Test
    void adminAndScheduledAdaptersUseTheSameMemeServiceWithDistinctOrigins() {
        MemeService memes = mock(MemeService.class);
        ScheduledTaskService tasks = new ScheduledTaskService(memes, backups, 456L);
        tasks.generateMeme(); tasks.generateAdminMeme();
        verify(memes).publish(new MemeService.Publish(MemeService.Origin.SCHEDULED, TelegramGateway.Destination.chat(456L)));
        verify(memes).publish(new MemeService.Publish(MemeService.Origin.ADMIN, TelegramGateway.Destination.chat(456L)));
        verifyNoMoreInteractions(memes);
    }

    @Test
    void realRepositoriesBuildReportWithoutTransportOrMissingAuthorFailures() {
        Author author = Author.builder().id(42L).userName("fixture").firstName("Author").build();
        db.insert(author);
        Message original = new Message();
        User user = new User(); user.setId(42L); original.setFrom(user);
        Source source = new Source(); source.setId(UUID.randomUUID()); source.setMessage(original);
        Source imported = new Source(); imported.setId(UUID.randomUUID());
        Source incomplete = new Source(); incomplete.setId(UUID.randomUUID()); incomplete.setMessage(new Message());
        Template template = new Template(); template.setId(UUID.randomUUID()); template.setMessage(original);
        db.insert(List.of(source, imported, incomplete), Source.class); db.insert(template);
        reports.send(new TelegramGateway.Destination(12L, 34, null));
        ArgumentCaptor<TelegramGateway.Text> text = ArgumentCaptor.forClass(TelegramGateway.Text.class);
        verify(telegram).sendText(text.capture());
        assertEquals(34, text.getValue().destination().replyToMessageId());
        assertTrue(text.getValue().html());
        assertTrue(text.getValue().text().contains("• Sources: 3"));
        assertTrue(text.getValue().text().contains("<b>@fixture</b>: 1 templates."));
        assertTrue(text.getValue().text().contains("<b>@fixture</b>: 1 sources."));
    }

    @Test
    void backupDeliversCapturedBundleThenReportWithConfiguredDestination() throws Exception {
        var snapshots=mock(com.boatarde.regatasimulator.migration.RecoverySnapshotService.class);
        Path bundle=Files.createDirectory(root.resolve("bundle"));
        Path part=Files.writeString(bundle.resolve("part.zip"),"synthetic");
        when(snapshots.capture()).thenReturn(bundle);
        org.springframework.test.util.ReflectionTestUtils.setField(backups,"snapshots",snapshots);
        backups.create();
        var order=inOrder(snapshots,telegram);
        order.verify(snapshots).capture(); order.verify(telegram).sendDocument(any()); order.verify(telegram).sendText(any());
        assertTrue(Files.exists(part));
        ArgumentCaptor<TelegramGateway.Text> text = ArgumentCaptor.forClass(TelegramGateway.Text.class);
        verify(telegram).sendText(text.capture());
        assertEquals(TelegramGateway.Destination.chat(456L), text.getValue().destination());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ping", "/report", "/backup"})
    void productionCommandDispatchCallsTypedServices(String command) {
        var request = TelegramTestFactory.buildCommandTextMessageUpdate(command);
        request.getMessage().setDate(100);
        PingService ping = mock(PingService.class);
        ReportService report = mock(ReportService.class);
        BackupService backup = mock(BackupService.class);
        long chat = request.getMessage().getChatId();
        TelegramRouter router = new TelegramRouter(ping, report, backup, null, null, null, telegram, chat);
        router.route(request, "fixture_bot");
        var destination = new TelegramGateway.Destination(chat, request.getMessage().getMessageId(),
            request.getMessage().getMessageThreadId());
        switch (command) {
            case "/ping" -> verify(ping).pong(destination, request.getMessage().getDate());
            case "/report" -> verify(report).send(destination);
            default -> verify(backup).create(destination);
        }
        verifyNoInteractions(telegram);
    }

    @Test void deliveryFailurePreservesIndependentLocalArtifactAndDoesNotSendReport() throws Exception {
        var snapshots=mock(com.boatarde.regatasimulator.migration.RecoverySnapshotService.class);
        Path bundle=Files.createDirectory(root.resolve("retained-bundle"));
        Path part=Files.writeString(bundle.resolve("part.zip"),"synthetic");
        when(snapshots.capture()).thenReturn(bundle);
        org.springframework.test.util.ReflectionTestUtils.setField(backups,"snapshots",snapshots);
        doThrow(new IllegalStateException("synthetic failure")).when(telegram).sendDocument(any());
        assertThrows(IllegalStateException.class,backups::create);
        assertTrue(Files.exists(part)); verify(telegram,never()).sendText(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jsondb", "templates", "sources"})
    void failureStopsLaterStagesAndCleansAllPreparedArchives(String prefix) throws Exception {
        Path first = Files.write(root.resolve("first.zip"), new byte[]{1});
        Path second = Files.write(root.resolve("second.zip"), new byte[]{2});
        var failure = new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "controlled failure");
        doThrow(failure).when(telegram).sendDocument(any());
        try (var files = mockStatic(FileUtils.class)) {
            files.when(() -> FileUtils.zipInChunks(anyString(), anyLong())).thenAnswer(call -> {
                String directory = call.getArgument(0);
                String stage = directory.endsWith("db") ? "jsondb" : Path.of(directory).getFileName().toString();
                return stage.equals(prefix) ? List.of(first, second) : List.of();
            });
            String directory=prefix.equals("jsondb") ? root.resolve("db").toString() : root.resolve(prefix).toString();
            assertSame(failure, assertThrows(ApplicationFailure.class, () -> backups.archive(directory,prefix)));
        }
        assertFalse(Files.exists(first)); assertFalse(Files.exists(second));
        verify(telegram).sendDocument(any()); verifyNoMoreInteractions(telegram);
    }
}