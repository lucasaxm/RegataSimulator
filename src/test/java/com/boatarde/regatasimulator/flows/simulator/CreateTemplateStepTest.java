package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateTemplateStepTest {

    private static final String CSV = """
        Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background
        1,1,0,1,20,1,20,30,0,30,1""";

    @TempDir
    private Path templatesRoot;
    @Mock
    private JsonDBTemplate database;
    @Mock
    private RegataSimulatorBot bot;

    private CreateTemplateStep step;

    @BeforeEach
    void setUp() {
        step = new CreateTemplateStep(templatesRoot.toString(), database, 10);
    }

    @Test
    void createsReviewTemplateWithParsedGeometryAndNextStep() throws TelegramApiException {
        CreatedTemplate created = createTemplate();
        Template template = created.template();

        assertEquals(WorkflowAction.GET_RANDOM_SOURCE, created.nextStep());
        assertNotNull(template.getId());
        assertEquals(Status.REVIEW, template.getStatus());
        assertEquals(10, template.getWeight());
        assertSame(created.update().getMessage(), template.getMessage());
        assertEquals(1, template.getAreas().size());
        TemplateArea area = template.getAreas().getFirst();
        assertEquals(1, area.getIndex());
        assertEquals(1, area.getSource());
        assertEquals(0, area.getTopLeft().getX());
        assertEquals(1, area.getTopLeft().getY());
        assertEquals(20, area.getTopRight().getX());
        assertEquals(1, area.getTopRight().getY());
        assertEquals(20, area.getBottomRight().getX());
        assertEquals(30, area.getBottomRight().getY());
        assertEquals(0, area.getBottomLeft().getX());
        assertEquals(30, area.getBottomLeft().getY());
        assertTrue(area.isBackground());
    }

    @Test
    void persistsAuthorAndSendsPortugueseProgressMessage() throws TelegramApiException {
        CreatedTemplate created = createTemplate();
        Author author = created.author();
        Message original = created.update().getMessage();
        SendMessage notification = created.notification();

        assertEquals(original.getFrom().getId(), author.getId());
        assertEquals("marinheiro", author.getUserName());
        assertEquals("Ana", author.getFirstName());
        assertEquals("Silva", author.getLastName());
        assertEquals("Criando template...", notification.getText());
        assertEquals(original.getChatId().toString(), notification.getChatId());
        assertEquals(original.getMessageId(), notification.getReplyToMessageId());
        assertEquals(Boolean.TRUE, notification.getAllowSendingWithoutReply());
    }

    @Test
    void storesDownloadedTemplateAndProgressInWorkflowBag() throws TelegramApiException {
        CreatedTemplate created = createTemplate();

        assertEquals(created.file(), created.bag().get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        assertSame(created.template(), created.bag().get(WorkflowDataKey.TEMPLATE, Template.class));
        assertSame(created.progress(), created.bag().get(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, Message.class));
        assertTrue(Files.exists(created.file()));
    }

    private CreatedTemplate createTemplate() throws TelegramApiException {
        Update update = submission(CSV);
        WorkflowDataBag bag = bag(update);
        Message progress = TelegramTestFactory.buildTextMessage("Criando template...");
        when(bot.execute(any(SendMessage.class))).thenReturn(progress);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubDownload(telegram);

            WorkflowAction nextStep = step.run(bag);

            ArgumentCaptor<Template> templateCaptor = ArgumentCaptor.forClass(Template.class);
            verify(database).insert(templateCaptor.capture());
            Template template = templateCaptor.getValue();

            ArgumentCaptor<Author> authorCaptor = ArgumentCaptor.forClass(Author.class);
            verify(database).upsert(authorCaptor.capture());
            Author author = authorCaptor.getValue();

            Path expectedFile = templatesRoot.resolve(template.getId().toString()).resolve("template.png");
            telegram.verify(() -> TelegramUtils.downloadTelegramFile(bot, "uploaded-file",
                expectedFile.getParent(), "template.png"));

            ArgumentCaptor<SendMessage> messageCaptor = ArgumentCaptor.forClass(SendMessage.class);
            verify(bot).execute(messageCaptor.capture());
            verifyNoMoreInteractions(database, bot);
            return new CreatedTemplate(update, bag, progress, nextStep, template, author,
                messageCaptor.getValue(), expectedFile);
        }
    }

    private record CreatedTemplate(Update update, WorkflowDataBag bag, Message progress, WorkflowAction nextStep,
                                   Template template, Author author, SendMessage notification, Path file) {
    }

    @Test
    void downloadFailureRemovesEmptyDirectoryAndDoesNotPersist() throws Exception {
        WorkflowDataBag bag = bag(submission(CSV));
        AtomicReference<Path> directory = new AtomicReference<>();

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), eq("uploaded-file"),
                any(Path.class), eq("template.png"))).thenAnswer(invocation -> {
                    directory.set(invocation.getArgument(2));
                    assertTrue(Files.isDirectory(directory.get()));
                    throw new TelegramApiException("download unavailable");
                });

            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNotNull(directory.get());
            assertFalse(Files.exists(directory.get()));
            assertNoCreatedTemplate(bag);
            assertNull(bag.get(WorkflowDataKey.SEND_MESSAGE, SendMessage.class));
            verifyNoInteractions(database, bot);
            try (var paths = Files.list(templatesRoot)) {
                assertEquals(0, paths.count());
            }
        }
    }

    @Test
    void partialDownloadFailureRemovesFilesAndDirectory() throws Exception {
        // Creation failure recursively compensates partial downloads.
        WorkflowDataBag bag = bag(submission(CSV));
        AtomicReference<Path> partialFile = new AtomicReference<>();

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), eq("uploaded-file"),
                any(Path.class), eq("template.png"))).thenAnswer(invocation -> {
                    Path directory = invocation.getArgument(2);
                    partialFile.set(Files.writeString(directory.resolve("template.png"), "partial"));
                    throw new IOException("interrupted copy");
                });

            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertFalse(Files.exists(partialFile.get()));
            assertFalse(Files.exists(partialFile.get().getParent()));
            assertNoCreatedTemplate(bag);
            verifyNoInteractions(database, bot);
        }
    }

    @Test
    void invalidCsvFailsBeforeDownloadingOrCreatingMedia() throws Exception {
        // Input validation precedes downloads; the runner reports application failures.
        WorkflowDataBag bag = bag(submission("invalid header"));

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNoCreatedTemplate(bag);
            assertNull(bag.get(WorkflowDataKey.SEND_MESSAGE, SendMessage.class));
            verifyNoInteractions(database, bot);
            telegram.verifyNoInteractions();
            assertRootEmpty();
        }
    }

    @Test
    void progressMessageFailureRemovesDownloadedMediaWithoutDatabaseWrites() throws Exception {
        // Media is compensated when Telegram fails before metadata insertion.
        when(bot.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("send unavailable"));
        WorkflowDataBag bag = bag(submission(CSV));

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubDownload(telegram);

            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNoCreatedTemplate(bag);
            verifyNoInteractions(database);
            assertRootEmpty();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"bounds", "author", "insert"})
    void boundsAndPersistenceFailuresCleanUncommittedTemplate(String failure) throws Exception {
        if (!failure.equals("bounds")) when(bot.execute(any(SendMessage.class)))
            .thenReturn(TelegramTestFactory.buildTextMessage("progress"));
        if (failure.equals("author")) doThrow(new IllegalStateException("author failure")).when(database).upsert(any(Author.class));
        if (failure.equals("insert")) doThrow(new IllegalStateException("insert failure")).when(database).insert(any(Template.class));
        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubDownload(telegram);
            WorkflowDataBag bag = bag(submission(failure.equals("bounds") ? CSV.replace("20", "500") : CSV));
            assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertNoCreatedTemplate(bag);
            assertRootEmpty();
            if (!failure.equals("insert")) verify(database, never()).insert(any(Template.class));
        }
    }

    private void stubDownload(MockedStatic<TelegramUtils> telegram) {
        telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), eq("uploaded-file"),
            any(Path.class), eq("template.png"))).thenAnswer(invocation -> {
                Path directory = invocation.getArgument(2);
                assertTrue(Files.isDirectory(directory));
                return ImageTestFactory.image(directory.resolve("template.png"));
            });
    }

    private Update submission(String caption) {
        Update update = TelegramTestFactory.buildTextMessageUpdate("upload");
        Message message = update.getMessage();
        message.setText(null);
        message.setCaption(caption);
        Document document = new Document();
        document.setFileId("uploaded-file");
        document.setFileName("Original.PNG");
        document.setMimeType("image/png");
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

    private void assertNoCreatedTemplate(WorkflowDataBag bag) {
        assertNull(bag.get(WorkflowDataKey.TEMPLATE_FILE, Path.class));
        assertNull(bag.get(WorkflowDataKey.TEMPLATE, Template.class));
        assertNull(bag.get(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, Message.class));
    }

    private void assertRootEmpty() throws IOException {
        try (var directories = Files.list(templatesRoot)) {
            List<Path> remaining = directories.toList();
            assertTrue(remaining.isEmpty());
        }
    }
}