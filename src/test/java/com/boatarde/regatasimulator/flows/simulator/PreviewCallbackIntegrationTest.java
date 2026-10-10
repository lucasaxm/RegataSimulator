package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowManager;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.routes.CreateSourceRoute;
import com.boatarde.regatasimulator.routes.CreateTemplateRoute;
import com.boatarde.regatasimulator.service.RouterService;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.TemplateService;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbSourceRepository;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbTemplateRepository;
import com.boatarde.regatasimulator.adapter.media.FileMediaStorage;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Real JsonDB, file cleanup, routing, and callbacks; Telegram and image bytes remain synthetic. */
class PreviewCallbackIntegrationTest {

    @TempDir
    private Path root;
    private JsonDBTemplate database;
    private RegataSimulatorBot bot;
    private RouterService router;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("db"));
        database = new JsonDBTemplate(root.resolve("db").toString(), "com.boatarde.regatasimulator.models");
        database.createCollection(Source.class);
        database.createCollection(Template.class);
        bot = mock(RegataSimulatorBot.class);
        var media = new FileMediaStorage(root.resolve("source").toString(), root.resolve("template").toString());
        SourceService sources = new SourceService(new JsonDbSourceRepository(database), media);
        TemplateService templates = new TemplateService(new JsonDbTemplateRepository(database), media);
        WorkflowManager manager = new WorkflowManager(List.of(
            new ConfirmReviewSourceStep("-9000", sources), new DeleteReviewSourceStep(sources),
            new ConfirmReviewTemplateStep("-9000", templates), new DeleteReviewTemplateStep(templates)));
        router = new RouterService(manager, List.of(new CreateSourceRoute(), new CreateTemplateRoute()));
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void newlySentPreviewIsBoundToStoredItemAndOwnerCanAct(String type, String action)
        throws IOException, TelegramApiException {
        CommonEntity item = sendPreview(type);
        CommonEntity stored = find(type, item.getId());
        assertThat(stored.getPreviewMessageId()).isEqualTo(900);
        assertThat(stored.getMessage().getMessageId()).isEqualTo(100);
        clearInvocations(bot);

        router.route(callback(item.getId() + ":" + type + ":" + action, 42L), bot);

        if (action.equals("confirm")) {
            CommonEntity confirmed = find(type, item.getId());
            assertThat(confirmed.getStatus()).isEqualTo(Status.REVIEW);
            assertThat(confirmed.getPreviewMessageId()).isNull();
            verify(bot).execute(any(SendPhoto.class));
        } else {
            assertThat(find(type, item.getId())).isNull();
            assertThat(root.resolve(type).resolve(item.getId().toString())).doesNotExist();
            verify(bot).execute(any(DeleteMessage.class));
        }
        verify(bot).execute(any(AnswerCallbackQuery.class));
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void routedMalformedOrUnauthorizedCallbacksOnlyAcknowledge(String type, String action)
        throws IOException, TelegramApiException {
        CommonEntity item = sendPreview(type);
        clearInvocations(bot);

        router.route(callback("1-1-1-1-1:" + type + ":" + action, 42L), bot);
        router.route(callback(item.getId() + ":" + type + ":" + action, 999L), bot);

        assertThat(find(type, item.getId()).getPreviewMessageId()).isEqualTo(900);
        assertThat(root.resolve(type).resolve(item.getId().toString()).resolve(type + ".png")).exists();
        ArgumentCaptor<AnswerCallbackQuery> answers = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(bot, times(2)).execute(answers.capture());
        assertThat(answers.getAllValues().getFirst().getText())
            .isEqualTo(answers.getAllValues().getLast().getText()).doesNotContain(item.getId().toString());
        verifyNoMoreInteractions(bot);
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void successfulConfirmationRemainsConsumedAfterDatabaseReload(String type, String replayAction)
        throws IOException, TelegramApiException {
        CommonEntity item = sendPreview(type);
        router.route(callback(item.getId() + ":" + type + ":confirm", 42L), bot);
        JsonDBTemplate reopened = new JsonDBTemplate(root.resolve("db").toString(),
            "com.boatarde.regatasimulator.models");
        CommonEntity confirmed = reopened.findById(item.getId(), itemClass(type));
        assertThat(confirmed.getPreviewMessageId()).isNull();
        clearInvocations(bot);

        router.route(callback(item.getId() + ":" + type + ":" + replayAction, 42L), bot);

        assertThat(find(type, item.getId()).getStatus()).isEqualTo(Status.REVIEW);
        verify(bot).execute(any(AnswerCallbackQuery.class));
        verifyNoMoreInteractions(bot);
    }

    private CommonEntity sendPreview(String type) throws IOException {
        return sendPreview(type, item -> { }, false);
    }

    @ParameterizedTest
    @CsvSource({"source", "template"})
    void approvalDuringPreviewDeliveryCannotBeOverwrittenByBindingPersistence(String type)
        throws IOException, TelegramApiException {
        CommonEntity item = sendPreview(type, submitted -> {
            CommonEntity approved = find(type, submitted.getId());
            approved.setStatus(Status.APPROVED);
            approved.setWeight(99);
            database.save(approved, itemClass(type));
        }, true);
        CommonEntity approved = find(type, item.getId());
        assertThat(approved.getStatus()).isEqualTo(Status.APPROVED);
        assertThat(approved.getWeight()).isEqualTo(99);
        assertThat(approved.getPreviewMessageId()).isNull();
        assertThat(approved.getMessage().getMessageId()).isEqualTo(100);
        clearInvocations(bot);

        router.route(callback(item.getId() + ":" + type + ":cancel", 42L), bot);

        verify(bot).execute(any(AnswerCallbackQuery.class));
        verifyNoMoreInteractions(bot);
        assertThat(find(type, item.getId()).getStatus()).isEqualTo(Status.APPROVED);
    }

    private CommonEntity sendPreview(String type, Consumer<CommonEntity> duringDelivery, boolean bindingFailure) throws IOException {
        CommonEntity item = type.equals("source") ? new Source() : new Template();
        item.setId(UUID.randomUUID());
        item.setStatus(Status.REVIEW);
        item.setWeight(10);
        Message original = message(100, 42L);
        item.setMessage(original);
        database.insert(item);
        Path directory = Files.createDirectories(root.resolve(type).resolve(item.getId().toString()));
        Path upload = Files.writeString(directory.resolve(type + ".png"), "synthetic uploaded media");
        Path output = Files.writeString(root.resolve("output.png"), "synthetic rendered media");
        WorkflowDataBag bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        bag.put(WorkflowDataKey.MEME_FILE, output);
        Update update = new Update();
        update.setMessage(original);
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);
        if (type.equals("source")) {
            bag.put(WorkflowDataKey.SOURCES, List.of(item));
            bag.put(WorkflowDataKey.SOURCE_FILES, List.of(upload));
            bag.put(WorkflowDataKey.CREATING_SOURCE_MESSAGE, message(200, 42L));
        } else {
            bag.put(WorkflowDataKey.TEMPLATE, item);
            bag.put(WorkflowDataKey.TEMPLATE_FILE, upload);
            bag.put(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, message(200, 42L));
        }
        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)))
                .thenAnswer(invocation -> {
                    duringDelivery.accept(item);
                    return message(900, 42L);
                });
            if (bindingFailure) {
                assertThrows(ApplicationFailure.class, () -> new SendMemeStep(-9000L, database).run(bag));
            } else {
                new SendMemeStep(-9000L, database).run(bag);
            }
        }
        assertThat(output).doesNotExist();
        return item;
    }

    private CommonEntity find(String type, UUID id) {
        return database.findById(id, itemClass(type));
    }

    private Class<? extends CommonEntity> itemClass(String type) {
        return type.equals("source") ? Source.class : Template.class;
    }

    private Update callback(String data, Long actor) {
        CallbackQuery query = new CallbackQuery();
        query.setId("callback-id");
        query.setData(data);
        query.setFrom(message(900, actor).getFrom());
        query.setMessage(message(900, 42L));
        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }

    private Message message(int id, Long actor) {
        Message message = TelegramTestFactory.buildTextMessage("fixture");
        message.setMessageId(id);
        User user = new User();
        user.setId(actor);
        user.setUserName("marinheiro");
        message.setFrom(user);
        PhotoSize photo = new PhotoSize();
        photo.setFileId("synthetic-preview-file");
        message.setPhoto(List.of(photo));
        return message;
    }
}