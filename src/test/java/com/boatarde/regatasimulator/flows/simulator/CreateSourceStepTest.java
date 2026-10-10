package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateSourceStepTest {

    @TempDir
    private Path sourcesRoot;
    @Mock
    private JsonDBTemplate database;
    @Mock
    private RegataSimulatorBot bot;
    @Mock
    private SourceService sourceService;

    private CreateSourceStep step;

    @BeforeEach
    void setUp() {
        step = new CreateSourceStep(sourcesRoot.toString(), database, 10, sourceService);
    }

    @Test
    void createsReviewSourceAndAuthorWithDownloadedPathAndNextStep() throws Exception {
        Update update = submission("SoUrCe:   Barco: azul   ");
        WorkflowDataBag bag = bag(update);
        Message progress = TelegramTestFactory.buildTextMessage("Criando source...");
        when(database.findAll(Source.class)).thenReturn(List.of());
        when(bot.execute(any(SendMessage.class))).thenReturn(progress);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubDownload(telegram);

            assertEquals(WorkflowAction.GET_RANDOM_TEMPLATE, step.run(bag));

            ArgumentCaptor<Source> sourceCaptor = ArgumentCaptor.forClass(Source.class);
            verify(database).insert(sourceCaptor.capture());
            Source source = sourceCaptor.getValue();
            assertNotNull(source.getId());
            assertEquals(Status.REVIEW, source.getStatus());
            assertEquals(10, source.getWeight());
            assertEquals("Barco: azul", source.getDescription());
            assertSame(update.getMessage(), source.getMessage());

            ArgumentCaptor<Author> authorCaptor = ArgumentCaptor.forClass(Author.class);
            verify(database).upsert(authorCaptor.capture());
            Author author = authorCaptor.getValue();
            assertEquals(update.getMessage().getFrom().getId(), author.getId());
            assertEquals("marinheiro", author.getUserName());
            assertEquals("Ana", author.getFirstName());
            assertEquals("Silva", author.getLastName());

            Path expectedFile = sourcesRoot.resolve(source.getId().toString()).resolve("source.jpeg");
            List<Path> files = bag.getGeneric(WorkflowDataKey.SOURCE_FILES, List.class, Path.class);
            List<Source> sources = bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class);
            assertEquals(List.of(expectedFile), files);
            assertSame(source, sources.getFirst());
            assertSame(progress, bag.get(WorkflowDataKey.CREATING_SOURCE_MESSAGE, Message.class));
            assertTrue(Files.exists(expectedFile));
            telegram.verify(() -> TelegramUtils.downloadTelegramFile(bot, "uploaded-file",
                expectedFile.getParent(), "source.jpeg"));

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(bot).execute(messageCaptor.capture());
            assertEquals("Criando source...", messageCaptor.getValue().getText());
            assertEquals(update.getMessage().getChatId().toString(), messageCaptor.getValue().getChatId());
            assertEquals(update.getMessage().getMessageId(), messageCaptor.getValue().getReplyToMessageId());
            assertEquals(Boolean.TRUE, messageCaptor.getValue().getAllowSendingWithoutReply());
            verifyNoMoreInteractions(database, bot);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "source", "source:", "source:   "})
    void emptyDescriptionQueuesPortugueseErrorWithoutDownloadOrPersistence(String caption) throws Exception {
        Update update = submission(caption);
        WorkflowDataBag bag = bag(update);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            assertEquals(WorkflowAction.SEND_MESSAGE_STEP, step.run(bag));

            SendMessage error = bag.get(WorkflowDataKey.SEND_MESSAGE, SendMessage.class);
            assertEquals("Erro: A descrição não pode estar vazia.", error.getText());
            assertEquals(update.getMessage().getChatId().toString(), error.getChatId());
            assertEquals(update.getMessage().getMessageId(), error.getReplyToMessageId());
            assertEquals(Boolean.TRUE, error.getAllowSendingWithoutReply());
            assertNoCreatedSource(bag);
            telegram.verifyNoInteractions();
            verifyNoInteractions(database, bot, sourceService);
            assertRootEmpty();
        }
    }

    @Test
    void caseInsensitiveDuplicateQueuesExistingPhotoInsteadOfCreatingSource() throws Exception {
        Source duplicate = new Source();
        duplicate.setId(UUID.randomUUID());
        duplicate.setDescription("Barco Azul");
        Source withoutDescription = new Source();
        when(database.findAll(Source.class)).thenReturn(List.of(withoutDescription, duplicate));
        when(sourceService.loadSourceAsResource(duplicate)).thenReturn(new ByteArrayResource(new byte[]{1, 2}));
        Update update = submission("source: bARCo aZUL");
        WorkflowDataBag bag = bag(update);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            assertEquals(WorkflowAction.SEND_PHOTO_STEP, step.run(bag));

            SendPhoto photo = bag.get(WorkflowDataKey.SEND_PHOTO, SendPhoto.class);
            assertNotNull(photo.getPhoto());
            assertTrue(photo.getCaption().contains("Já existe uma source com esta descrição."));
            assertTrue(photo.getCaption().contains(duplicate.getId().toString()));
            assertTrue(photo.getCaption().contains(duplicate.getDescription()));
            assertEquals(update.getMessage().getChatId().toString(), photo.getChatId());
            assertEquals(update.getMessage().getMessageId(), photo.getReplyToMessageId());
            assertEquals(Boolean.TRUE, photo.getAllowSendingWithoutReply());
            assertNoCreatedSource(bag);
            verify(database).findAll(Source.class);
            verify(sourceService).loadSourceAsResource(duplicate);
            verifyNoMoreInteractions(database, sourceService);
            verifyNoInteractions(bot);
            telegram.verifyNoInteractions();
            // The step hands this stream to SEND_PHOTO_STEP; close it in the test fixture.
            photo.getPhoto().getNewMediaStream().close();
            assertRootEmpty();
        }
    }

    @Test
    void duplicateImageFailureRaisesApplicationFailureForRouterReporting() throws Exception {
        // The runner, not this preparation step, sends the generic failure notification.
        Source duplicate = new Source();
        duplicate.setDescription("Barco");
        when(database.findAll(Source.class)).thenReturn(List.of(duplicate));
        when(sourceService.loadSourceAsResource(duplicate)).thenThrow(new IllegalStateException("missing image"));
        WorkflowDataBag bag = bag(submission("source: barco"));

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNull(bag.get(WorkflowDataKey.SEND_MESSAGE, SendMessage.class));
            assertNull(bag.get(WorkflowDataKey.SEND_PHOTO, SendPhoto.class));
            assertNoCreatedSource(bag);
            verify(database).findAll(Source.class);
            verifyNoMoreInteractions(database);
            verifyNoInteractions(bot);
            telegram.verifyNoInteractions();
            assertRootEmpty();
        }
    }

    @Test
    void downloadFailureRemovesEmptyDirectoryAndDoesNotPersist() throws Exception {
        when(database.findAll(Source.class)).thenReturn(List.of());
        WorkflowDataBag bag = bag(submission("source: Barco"));
        AtomicReference<Path> directory = new AtomicReference<>();

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), eq("uploaded-file"),
                any(Path.class), eq("source.jpeg"))).thenAnswer(invocation -> {
                    directory.set(invocation.getArgument(2));
                    assertTrue(Files.isDirectory(directory.get()));
                    throw new TelegramApiException("download unavailable");
                });

            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNotNull(directory.get());
            assertFalse(Files.exists(directory.get()));
            assertNoCreatedSource(bag);
            assertNull(bag.get(WorkflowDataKey.SEND_MESSAGE, SendMessage.class));
            verify(database).findAll(Source.class);
            verifyNoMoreInteractions(database);
            verifyNoInteractions(bot);
            assertRootEmpty();
        }
    }

    @Test
    void partialDownloadFailureRemovesFilesAndDirectory() throws Exception {
        // Cleanup removes partial files as well as their parent.
        when(database.findAll(Source.class)).thenReturn(List.of());
        WorkflowDataBag bag = bag(submission("source: Barco"));
        AtomicReference<Path> partialFile = new AtomicReference<>();

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), eq("uploaded-file"),
                any(Path.class), eq("source.jpeg"))).thenAnswer(invocation -> {
                    Path directory = invocation.getArgument(2);
                    partialFile.set(Files.writeString(directory.resolve("source.jpeg"), "partial"));
                    throw new IOException("interrupted copy");
                });

            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertFalse(Files.exists(partialFile.get()));
            assertFalse(Files.exists(partialFile.get().getParent()));
            assertNoCreatedSource(bag);
            verify(database).findAll(Source.class);
            verifyNoMoreInteractions(database);
            verifyNoInteractions(bot);
        }
    }

    @Test
    void progressMessageFailureRemovesDownloadedMediaWithoutDatabaseWrites() throws Exception {
        // Downloaded media is compensated when Telegram fails before metadata insertion.
        when(database.findAll(Source.class)).thenReturn(List.of());
        when(bot.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("send unavailable"));
        WorkflowDataBag bag = bag(submission("source: Barco"));

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubDownload(telegram);

            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNoCreatedSource(bag);
            verify(database).findAll(Source.class);
            verifyNoMoreInteractions(database);
            try (var directories = Files.list(sourcesRoot)) {
                List<Path> remaining = directories.toList();
                assertTrue(remaining.isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalidImage", "author", "insert"})
    void invalidImageAndPersistenceFailuresCleanUncommittedMedia(String failure) throws Exception {
        when(database.findAll(Source.class)).thenReturn(List.of());
        if (!failure.equals("invalidImage")) {
            when(bot.execute(any(SendMessage.class))).thenReturn(TelegramTestFactory.buildTextMessage("progress"));
        }
        if (failure.equals("author")) doThrow(new IllegalStateException("author failure")).when(database).upsert(any(Author.class));
        if (failure.equals("insert")) doThrow(new IllegalStateException("insert failure")).when(database).insert(any(Source.class));
        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            if (failure.equals("invalidImage")) {
                telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), anyString(), any(Path.class), anyString()))
                    .thenAnswer(invocation -> Files.writeString(((Path) invocation.getArgument(2)).resolve("source.jpeg"), "fake"));
            } else stubDownload(telegram);
            WorkflowDataBag bag = bag(submission("source: valid name"));
            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNoCreatedSource(bag);
            assertRootEmpty();
            if (!failure.equals("insert")) verify(database, never()).insert(any(Source.class));
        }
    }

    private void stubDownload(MockedStatic<TelegramUtils> telegram) {
        telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), eq("uploaded-file"),
            any(Path.class), eq("source.jpeg"))).thenAnswer(invocation -> {
                Path directory = invocation.getArgument(2);
                assertTrue(Files.isDirectory(directory));
                return ImageTestFactory.image(directory.resolve("source.jpeg"));
            });
    }

    private Update submission(String caption) {
        Update update = TelegramTestFactory.buildTextMessageUpdate("upload");
        Message message = update.getMessage();
        message.setText(null);
        message.setCaption(caption);
        Document document = new Document();
        document.setFileId("uploaded-file");
        document.setFileName("Original.JPEG");
        document.setMimeType("image/jpeg");
        message.setDocument(document);
        User user = new User();
        user.setId(42L);
        user.setFirstName("Ana");
        user.setLastName("Silva");
        user.setUserName("marinheiro");
        user.setIsBot(false);
        message.setFrom(user);
        return update;
    }

    private WorkflowDataBag bag(Update update) {
        WorkflowDataBag bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);
        bag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        return bag;
    }

    private void assertNoCreatedSource(WorkflowDataBag bag) {
        assertNull(bag.get(WorkflowDataKey.SOURCE_FILES, List.class));
        assertNull(bag.get(WorkflowDataKey.SOURCES, List.class));
        assertNull(bag.get(WorkflowDataKey.CREATING_SOURCE_MESSAGE, Message.class));
    }

    private void assertRootEmpty() throws IOException {
        try (var paths = Files.list(sourcesRoot)) {
            assertEquals(0, paths.count());
        }
    }
}