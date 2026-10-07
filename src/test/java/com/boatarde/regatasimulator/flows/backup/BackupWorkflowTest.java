package com.boatarde.regatasimulator.flows.backup;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowManager;
import com.boatarde.regatasimulator.flows.common.SendMessageStep;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.BackupService;
import com.boatarde.regatasimulator.service.RouterService;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Phase 0: real router, registry, all four backup/report steps and the common sender. */
@ExtendWith(MockitoExtension.class)
class BackupWorkflowTest {

    @TempDir
    private Path tempDir;
    @Mock
    private BackupService backup;
    @Mock
    private JsonDBTemplate db;
    @Mock
    private RegataSimulatorBot bot;

    private String dbPath;
    private String templatesPath;
    private String sourcesPath;
    private RouterService router;
    private Update update;

    @BeforeEach
    void setUp() throws IOException {
        dbPath = Files.createDirectories(tempDir.resolve("db")).toString();
        templatesPath = Files.createDirectories(tempDir.resolve("templates")).toString();
        sourcesPath = Files.createDirectories(tempDir.resolve("sources")).toString();
        WorkflowManager manager = new WorkflowManager(List.of(
            new BackupJsonDBStep(dbPath, backup),
            new BackupTemplatesStep(templatesPath, backup),
            new BackupSourcesStep(sourcesPath, backup),
            new SendReportStep(db),
            new SendMessageStep()));
        router = new RouterService(manager, List.of());
        update = new Update();
        update.setMessage(origin());
    }

    @Test
    void realWorkflowBacksUpDatabaseThenTemplatesThenSourcesThenBuildsAndSendsReport() throws Exception {
        reportData();
        when(bot.execute(any(SendMessage.class))).thenReturn(origin());

        router.startFlow(update, bot, WorkflowAction.BACKUP_JSON_DB_STEP);

        verifyFullOrderAndReport();
    }

    @ParameterizedTest
    @CsvSource({"jsondb,io", "jsondb,telegram", "templates,io", "templates,telegram", "sources,io", "sources,telegram"})
    void firstCheckedFailureCurrentlyIsSwallowedAndTerminatesRealWorkflowPhase1ObservabilityGap(
        String failingStage, String failureType) throws Exception {
        Exception failure = "io".equals(failureType)
            ? new IOException("controlled backup failure")
            : new TelegramApiException("controlled backup failure");
        String failingPath = switch (failingStage) {
            case "jsondb" -> dbPath;
            case "templates" -> templatesPath;
            default -> sourcesPath;
        };
        doAnswer(invocation -> {
            if (failingPath.equals(invocation.getArgument(1)) && failingStage.equals(invocation.getArgument(2))) {
                throw failure;
            }
            return null;
        }).when(backup).zipToTelegram(eq(bot), anyString(), anyString());

        // The real step returns NONE; RouterService exits normally rather than surfacing the failure.
        assertDoesNotThrow(() -> router.startFlow(update, bot, WorkflowAction.BACKUP_JSON_DB_STEP));

        InOrder order = inOrder(backup);
        order.verify(backup).zipToTelegram(bot, dbPath, "jsondb");
        if (!"jsondb".equals(failingStage)) {
            order.verify(backup).zipToTelegram(bot, templatesPath, "templates");
        }
        if ("sources".equals(failingStage)) {
            order.verify(backup).zipToTelegram(bot, sourcesPath, "sources");
        }
        verifyNoMoreInteractions(backup);
        verifyNoInteractions(db, bot);
    }

    @Test
    void uncheckedBackupFailureCurrentlyEscapesRealRouterAndStopsLaterSteps() throws Exception {
        IllegalStateException failure = new IllegalStateException("unchecked failure");
        doThrow(failure).when(backup).zipToTelegram(bot, dbPath, "jsondb");

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> router.startFlow(update, bot, WorkflowAction.BACKUP_JSON_DB_STEP)));

        inOrder(backup).verify(backup).zipToTelegram(bot, dbPath, "jsondb");
        verifyNoMoreInteractions(backup);
        verifyNoInteractions(db, bot);
    }

    @Test
    void reportDeliveryFailureCurrentlyIsSwallowedByRealCommonSenderPhase1ObservabilityGap() throws Exception {
        reportData();
        when(bot.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("report delivery failed"));

        assertDoesNotThrow(() -> router.startFlow(update, bot, WorkflowAction.BACKUP_JSON_DB_STEP));

        verifyFullOrderAndReport();
    }

    @Test
    void missingReportOriginCurrentlyThrowsAfterAllBackupsPhase1NullOriginBug() throws Exception {
        update.setMessage(null);

        assertThrows(NullPointerException.class,
            () -> router.startFlow(update, bot, WorkflowAction.BACKUP_JSON_DB_STEP));

        InOrder order = inOrder(backup);
        order.verify(backup).zipToTelegram(bot, dbPath, "jsondb");
        order.verify(backup).zipToTelegram(bot, templatesPath, "templates");
        order.verify(backup).zipToTelegram(bot, sourcesPath, "sources");
        verifyNoMoreInteractions(backup);
        verifyNoInteractions(db, bot);
    }

    private void reportData() {
        Template template = new Template();
        template.setMessage(origin());
        Source submitted = new Source();
        submitted.setMessage(origin());
        Source imported = new Source();
        imported.setMessage(null);
        Author author = Author.builder().id(1L).firstName("Test author").userName("test_author").build();
        when(db.findAll(Template.class)).thenReturn(List.of(template));
        when(db.findAll(Source.class)).thenReturn(List.of(submitted, imported));
        when(db.findAll(Author.class)).thenReturn(List.of(author));
    }

    private void verifyFullOrderAndReport() throws Exception {
        ArgumentCaptor<SendMessage> report = ArgumentCaptor.forClass(SendMessage.class);
        InOrder order = inOrder(backup, db, bot);
        order.verify(backup).zipToTelegram(bot, dbPath, "jsondb");
        order.verify(backup).zipToTelegram(bot, templatesPath, "templates");
        order.verify(backup).zipToTelegram(bot, sourcesPath, "sources");
        order.verify(db).findAll(Template.class);
        order.verify(db).findAll(Source.class);
        order.verify(db).findAll(Author.class);
        order.verify(bot).execute(report.capture());
        SendMessage message = report.getValue();
        assertEquals("123", message.getChatId());
        assertEquals(42, message.getReplyToMessageId());
        assertEquals("HTML", message.getParseMode());
        assertEquals(Boolean.TRUE, message.getAllowSendingWithoutReply());
        assertTrue(message.getText().contains("• Templates: 1"));
        assertTrue(message.getText().contains("• Sources: 2"));
        assertTrue(message.getText().contains("<b>@test_author</b>: 1 templates."));
        assertTrue(message.getText().contains("<b>@test_author</b>: 1 sources."));
        verifyNoMoreInteractions(backup, db, bot);
    }

    private Message origin() {
        User user = new User();
        user.setId(1L);
        user.setFirstName("Test author");
        user.setIsBot(false);
        Chat chat = new Chat();
        chat.setId(123L);
        chat.setType("private");
        Message message = new Message();
        message.setMessageId(42);
        message.setDate(100);
        message.setChat(chat);
        message.setFrom(user);
        return message;
    }
}