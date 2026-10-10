package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.util.TelegramUtils;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class SendMemeStepTest {

    private static final Long CHANNEL_ID = -9000L;

    @TempDir
    private Path temporaryDirectory;
    @Mock
    private RegataSimulatorBot bot;
    @Mock
    private JsonDBTemplate database;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationUsesExpectedDeliveryMetadataAndCleansOutput(boolean hasUpdate) throws IOException {
        Publication publication = publish(hasUpdate);
        SendPhoto photo = publication.photo();
        Message original = publication.update().getMessage();

        assertEquals(publication.output().toFile(), photo.getPhoto().getNewMediaFile());
        assertNull(photo.getReplyMarkup());
        if (hasUpdate) {
            assertEquals(original.getChatId().toString(), photo.getChatId());
            assertEquals(original.getMessageId(), photo.getReplyToMessageId());
            assertEquals(original.getMessageThreadId(), photo.getMessageThreadId());
            assertEquals(Boolean.TRUE, photo.getAllowSendingWithoutReply());
        } else {
            assertEquals(CHANNEL_ID.toString(), photo.getChatId());
            assertNull(photo.getReplyToMessageId());
        }
        assertFalse(Files.exists(publication.output()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicationSendsBeforeUpdatingWeightsAndOrderedHistory(boolean hasUpdate) throws IOException {
        Publication publication = publish(hasUpdate);
        Template template = publication.template();
        List<Source> sources = publication.sources();
        Meme saved = publication.saved();

        assertEquals(2, template.getWeight());
        assertEquals(1, sources.getFirst().getWeight());
        assertEquals(1, sources.getLast().getWeight());
        assertNotNull(saved.getId());
        assertEquals(template.getId(), saved.getTemplateId());
        assertEquals(sources.stream().map(Source::getId).toList(), saved.getSourceIds());
        assertSame(publication.response(), saved.getMessage());
    }

    private Publication publish(boolean hasUpdate) throws IOException {
        Path output = output();
        WorkflowDataBag bag = publicationBag(output);
        Update update = TelegramTestFactory.buildTextMessageUpdate("/meme");
        update.getMessage().setMessageThreadId(77);
        if (hasUpdate) {
            bag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);
        }
        Template template = bag.get(WorkflowDataKey.TEMPLATE, Template.class);
        List<Source> sources = bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class);
        Message response = TelegramTestFactory.buildTextMessage("published");
        AtomicReference<SendPhoto> sent = new AtomicReference<>();
        AtomicBoolean sendCompleted = new AtomicBoolean();
        doAnswer(invocation -> {
            assertTrue(sendCompleted.get(), "weight persistence must follow a successful send");
            return null;
        }).when(database).upsert(any());
        doAnswer(invocation -> {
            assertTrue(sendCompleted.get(), "history persistence must follow a successful send");
            return null;
        }).when(database).insert(any());

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)))
                .thenAnswer(invocation -> {
                    sent.set(invocation.getArgument(1));
                    assertTrue(Files.exists(output));
                    verifyNoInteractions(database);
                    assertEquals(3, template.getWeight());
                    assertEquals(2, sources.getFirst().getWeight());
                    assertEquals(1, sources.getLast().getWeight());
                    sendCompleted.set(true);
                    return response;
                });

            assertEquals(WorkflowAction.NONE, new SendMemeStep(CHANNEL_ID, database).run(bag));

            assertNotNull(sent.get());
            ArgumentCaptor<Meme> memeCaptor = ArgumentCaptor.forClass(Meme.class);
            InOrder order = inOrder(database);
            order.verify(database).upsert(sources.getFirst());
            order.verify(database).upsert(template);
            order.verify(database).insert(memeCaptor.capture());
            order.verifyNoMoreInteractions();
            telegram.verify(() -> TelegramUtils.executeSendMediaBotMethod(bot, sent.get()));
            verifyNoInteractions(bot);
            return new Publication(output, update, template, sources, response, sent.get(), memeCaptor.getValue());
        }
    }

    private record Publication(Path output, Update update, Template template, List<Source> sources,
                               Message response, SendPhoto photo, Meme saved) {
    }

    @ParameterizedTest
    @CsvSource({"999,false", "1000,false", "1000,true"})
    void characterizesHistoryTrimmingAtLimitBeforeNewMemeInsertion(int historySize, boolean removalFails)
        throws Exception {
        // Phase1: history retention/storage failures need an explicit recovery policy.
        Path output = output();
        WorkflowDataBag bag = publicationBag(output);
        Template template = bag.get(WorkflowDataKey.TEMPLATE, Template.class);
        List<Source> sources = bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class);
        template.setWeight(1);
        sources.forEach(source -> source.setWeight(1));
        List<Meme> history = new ArrayList<>();
        for (int index = 0; index < historySize; index++) {
            history.add(Meme.builder().id(UUID.randomUUID()).build());
        }
        bag.put(WorkflowDataKey.MEMES_HISTORY, history);
        Meme oldest = history.getLast();
        if (historySize >= 1000) {
            when(database.remove(oldest, Meme.class)).thenReturn(removalFails ? null : oldest);
        }

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)))
                .thenAnswer(invocation -> {
                    verifyNoInteractions(database);
                    return TelegramTestFactory.buildTextMessage("published");
                });

            assertEquals(WorkflowAction.NONE, new SendMemeStep(CHANNEL_ID, database).run(bag));

            InOrder order = inOrder(database);
            if (historySize >= 1000) {
                order.verify(database).remove(oldest, Meme.class);
            }
            order.verify(database).insert(any(Meme.class));
            order.verifyNoMoreInteractions();
            verifyNoMoreInteractions(database);
            assertEquals(historySize, history.size());
            assertEquals(1, template.getWeight());
            assertTrue(sources.stream().allMatch(source -> source.getWeight() == 1));
            assertFalse(Files.exists(output));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void previewSendsInlineConfirmCancelAndPersistsOnlyItsBinding(String type) throws Exception {
        Path output = output();
        WorkflowDataBag bag = publicationBag(output);
        Update update = TelegramTestFactory.buildTextMessageUpdate("submission");
        update.getMessage().setMessageThreadId(77);
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);
        UUID itemId = UUID.randomUUID();
        Path original = previewPath(type, itemId);
        Message progress = addPreviewToBag(bag, type, original);
        CommonEntity item = previewItem(bag, type);
        Message origin = item.getMessage();
        Message response = TelegramTestFactory.buildTextMessage("preview");
        stubStoredPreview(type, item);
        AtomicReference<SendPhoto> sent = new AtomicReference<>();

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)))
                .thenAnswer(invocation -> {
                    verify(bot).execute(any(DeleteMessage.class));
                    sent.set(invocation.getArgument(1));
                    assertTrue(Files.exists(output));
                    verifyNoInteractions(database);
                    return response;
                });

            assertEquals(WorkflowAction.NONE, new SendMemeStep(CHANNEL_ID, database).run(bag));

            ArgumentCaptor<DeleteMessage> delete = ArgumentCaptor.forClass(DeleteMessage.class);
            verify(bot).execute(delete.capture());
            assertEquals(progress.getChatId().toString(), delete.getValue().getChatId());
            assertEquals(progress.getMessageId(), delete.getValue().getMessageId());
            verifyNoMoreInteractions(bot);
            assertNotNull(sent.get());
            assertEquals(update.getMessage().getChatId().toString(), sent.get().getChatId());
            assertEquals(update.getMessage().getMessageId(), sent.get().getReplyToMessageId());
            assertEquals(update.getMessage().getMessageThreadId(), sent.get().getMessageThreadId());
            assertEquals(Boolean.TRUE, sent.get().getAllowSendingWithoutReply());
            InlineKeyboardMarkup markup = (InlineKeyboardMarkup) sent.get().getReplyMarkup();
            assertNotNull(markup);
            assertEquals(2, markup.getKeyboard().size());
            InlineKeyboardButton confirm = markup.getKeyboard().getFirst().getFirst();
            InlineKeyboardButton cancel = markup.getKeyboard().getLast().getFirst();
            assertEquals("✅ Confirmar", confirm.getText());
            assertEquals(itemId + ":" + type + ":confirm", confirm.getCallbackData());
            assertEquals("❌ Cancelar", cancel.getText());
            assertEquals(itemId + ":" + type + ":cancel", cancel.getCallbackData());
            assertEquals(1, markup.getKeyboard().getFirst().size());
            assertEquals(1, markup.getKeyboard().getLast().size());
            verifyPreviewUpdate(type, item.getId(), response);
            assertEquals(response.getChatId(), item.getPreviewChatId());
            assertEquals(response.getMessageId(), item.getPreviewMessageId());
            assertSame(origin, item.getMessage());
            assertWeightsUnchanged(bag);
            assertFalse(Files.exists(output));
            assertTrue(Files.exists(original), "preview cleanup must preserve the uploaded item");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"publication", "source", "template"})
    void mediaApiFailureLogsAndCleansOutputWithoutDatabaseWrites(String mode, CapturedOutput capturedOutput)
        throws Exception {
        Path output = output();
        WorkflowDataBag bag = publicationBag(output);
        Path original = null;
        if (!mode.equals("publication")) {
            bag.put(WorkflowDataKey.TELEGRAM_UPDATE, TelegramTestFactory.buildTextMessageUpdate("submission"));
            original = previewPath(mode, UUID.randomUUID());
            addPreviewToBag(bag, mode, original);
        }

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)))
                .thenThrow(new TelegramApiException("media transport unavailable"));

            SendMemeStep step = new SendMemeStep(CHANNEL_ID, database);
            ApplicationFailure failure = assertThrows(ApplicationFailure.class, () -> step.run(bag));
            assertEquals("media transport unavailable", failure.getCause().getMessage());

            telegram.verify(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)));
            verifyNoInteractions(database);
            assertWeightsUnchanged(bag);
            assertFalse(Files.exists(output));
            if (original != null) {
                assertTrue(Files.exists(original));
                verify(bot).execute(any(DeleteMessage.class));
            }
            verifyNoMoreInteractions(bot);
            assertFalse(capturedOutput.getAll().contains("media transport unavailable"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void progressDeletionFailureDoesNotPreventPreviewSendOrBinding(String type) throws Exception {
        Path output = output();
        WorkflowDataBag bag = publicationBag(output);
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, TelegramTestFactory.buildTextMessageUpdate("submission"));
        Path original = previewPath(type, UUID.randomUUID());
        addPreviewToBag(bag, type, original);
        CommonEntity item = previewItem(bag, type);
        stubStoredPreview(type, item);
        Message response = TelegramTestFactory.buildTextMessage("preview");
        doThrow(new TelegramApiException("cannot delete progress")).when(bot).execute(any(DeleteMessage.class));

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class))).thenReturn(response);
            assertEquals(WorkflowAction.NONE, new SendMemeStep(CHANNEL_ID, database).run(bag));

            telegram.verify(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)));
            verifyPreviewUpdate(type, item.getId(), response);
            assertWeightsUnchanged(bag);
            assertFalse(Files.exists(output));
            assertTrue(Files.exists(original));
        }
    }

    @ParameterizedTest
    @CsvSource({"source,nullMessage", "template,nullMessage", "source,nullChat", "template,nullChat",
        "source,nullMessageId", "template,nullMessageId", "source,zeroId", "template,zeroId"})
    void invalidPreviewResponseLeavesBindingDisabledWithoutDatabaseCalls(String type, String invalidIdentity)
        throws Exception {
        Preview preview = preview(type);
        Message response = TelegramTestFactory.buildTextMessage("preview");
        switch (invalidIdentity) {
            case "nullMessage" -> response = null;
            case "nullChat" -> response.setChat(null);
            case "nullMessageId" -> response.setMessageId(null);
            case "zeroId" -> response.setMessageId(0);
            default -> throw new IllegalArgumentException(invalidIdentity);
        }

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubPreviewSend(telegram, preview, response);

            assertThrows(ApplicationFailure.class, () -> new SendMemeStep(CHANNEL_ID, database).run(preview.bag()));

            verifyNoInteractions(database);
            assertNull(preview.item().getPreviewChatId());
            assertNull(preview.item().getPreviewMessageId());
            assertPreviewPreserved(preview);
        }
    }

    @ParameterizedTest
    @CsvSource({"source,missing", "template,missing", "source,approved", "template,approved"})
    void absentOrApprovedStoredPreviewRejectsAtomicUpdate(String type, String storedState) throws Exception {
        Preview preview = preview(type);
        CommonEntity stored = storedState.equals("missing") ? null : storedPreview(preview);
        if (stored != null) {
            stored.setStatus(Status.APPROVED);
            stored.setPreviewChatId(-7000L);
            stored.setPreviewMessageId(71);
        }
        Message response = TelegramTestFactory.buildTextMessage("preview");
        if (type.equals("source")) {
            when(database.findAndModify(eq(previewQuery(preview.item().getId())),
                any(io.jsondb.query.Update.class), eq(Source.class))).thenReturn(null);
        } else {
            when(database.findAndModify(eq(previewQuery(preview.item().getId())),
                any(io.jsondb.query.Update.class), eq(Template.class))).thenReturn(null);
        }

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubPreviewSend(telegram, preview, response);

            assertThrows(ApplicationFailure.class, () -> new SendMemeStep(CHANNEL_ID, database).run(preview.bag()));

            verifyPreviewUpdate(type, preview.item().getId(), response);
            assertNull(preview.item().getPreviewChatId());
            assertNull(preview.item().getPreviewMessageId());
            if (stored != null) {
                assertEquals(Status.APPROVED, stored.getStatus());
                assertEquals(-7000L, stored.getPreviewChatId());
                assertEquals(71, stored.getPreviewMessageId());
                assertSame(preview.origin(), stored.getMessage());
                assertEquals(preview.item().getWeight(), stored.getWeight());
            }
            assertPreviewPreserved(preview);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void atomicPreviewMetadataFailurePropagatesAndFinallyCleansOutput(String type)
        throws Exception {
        Preview preview = preview(type);
        Message response = TelegramTestFactory.buildTextMessage("preview");
        IllegalStateException failure = new IllegalStateException("atomic preview metadata update failed");
        if (type.equals("source")) {
            when(database.findAndModify(eq(previewQuery(preview.item().getId())),
                any(io.jsondb.query.Update.class), eq(Source.class))).thenThrow(failure);
        } else {
            when(database.findAndModify(eq(previewQuery(preview.item().getId())),
                any(io.jsondb.query.Update.class), eq(Template.class))).thenThrow(failure);
        }

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubPreviewSend(telegram, preview, response);

            assertSame(failure, assertThrowsExactly(IllegalStateException.class,
                () -> new SendMemeStep(CHANNEL_ID, database).run(preview.bag())));

            verifyPreviewUpdate(type, preview.item().getId(), response);
            assertNull(preview.item().getPreviewChatId());
            assertNull(preview.item().getPreviewMessageId());
            assertPreviewPreserved(preview);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void previewCallbackAndAtomicQueryUseEntityIdRatherThanUploadDirectory(String type) throws Exception {
        Preview preview = preview(type);
        UUID uploadId = UUID.fromString(preview.original().getParent().getFileName().toString());
        UUID entityId = UUID.randomUUID();
        preview.item().setId(entityId);
        assertNotEquals(uploadId, entityId);
        stubStoredPreview(type, preview.item());
        Message response = TelegramTestFactory.buildTextMessage("preview");

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubPreviewSend(telegram, preview, response);

            assertEquals(WorkflowAction.NONE, new SendMemeStep(CHANNEL_ID, database).run(preview.bag()));

            ArgumentCaptor<SendPhoto> photo = ArgumentCaptor.forClass(SendPhoto.class);
            telegram.verify(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), photo.capture()));
            InlineKeyboardMarkup markup = (InlineKeyboardMarkup) photo.getValue().getReplyMarkup();
            assertEquals(entityId + ":" + type + ":confirm",
                markup.getKeyboard().getFirst().getFirst().getCallbackData());
            assertEquals(entityId + ":" + type + ":cancel",
                markup.getKeyboard().getLast().getFirst().getCallbackData());
            verifyPreviewUpdate(type, entityId, response);
            assertEquals(response.getChatId(), preview.item().getPreviewChatId());
            assertEquals(response.getMessageId(), preview.item().getPreviewMessageId());
            assertPreviewPreserved(preview);
        }
    }

    private Preview preview(String type) throws IOException {
        Path output = output();
        WorkflowDataBag bag = publicationBag(output);
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, TelegramTestFactory.buildTextMessageUpdate("submission"));
        Path original = previewPath(type, UUID.randomUUID());
        addPreviewToBag(bag, type, original);
        CommonEntity item = previewItem(bag, type);
        Message origin = item.getMessage();
        origin.setMessageId(41);
        origin.getChat().setId(-8000L);
        List<Meme> history = List.of(Meme.builder().id(UUID.randomUUID()).build());
        bag.put(WorkflowDataKey.MEMES_HISTORY, new ArrayList<>(history));
        return new Preview(bag, item, origin, output, original, history);
    }

    private void stubPreviewSend(MockedStatic<TelegramUtils> telegram, Preview preview, Message response) {
        telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)))
            .thenAnswer(invocation -> {
                verifyNoInteractions(database);
                assertWeightsUnchanged(preview.bag());
                assertEquals(preview.history(), preview.bag().getGeneric(WorkflowDataKey.MEMES_HISTORY,
                    List.class, Meme.class));
                assertNull(preview.item().getPreviewChatId());
                assertNull(preview.item().getPreviewMessageId());
                assertSame(preview.origin(), preview.item().getMessage());
                assertTrue(Files.exists(preview.output()));
                return response;
            });
    }

    private CommonEntity storedPreview(Preview preview) {
        CommonEntity stored = preview.item() instanceof Source ? new Source() : new Template();
        stored.setId(preview.item().getId());
        stored.setStatus(Status.REVIEW);
        stored.setMessage(preview.origin());
        stored.setWeight(preview.item().getWeight());
        return stored;
    }

    private void assertPreviewPreserved(Preview preview) {
        assertSame(preview.origin(), preview.item().getMessage());
        assertEquals(-8000L, preview.origin().getChatId());
        assertEquals(41, preview.origin().getMessageId());
        assertEquals(Status.REVIEW, preview.item().getStatus());
        assertWeightsUnchanged(preview.bag());
        assertEquals(preview.history(), preview.bag().getGeneric(WorkflowDataKey.MEMES_HISTORY,
            List.class, Meme.class));
        assertFalse(Files.exists(preview.output()));
        assertTrue(Files.exists(preview.original()));
    }

    private record Preview(WorkflowDataBag bag, CommonEntity item, Message origin, Path output, Path original,
                           List<Meme> history) {
    }

    private Path output() throws IOException {
        return Files.writeString(temporaryDirectory.resolve("rendered-output.png"), "not a real image");
    }

    private Path previewPath(String type, UUID itemId) throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve(itemId.toString()));
        return Files.writeString(directory.resolve(type + ".png"), "original upload");
    }

    private Message addPreviewToBag(WorkflowDataBag bag, String type, Path original) {
        Message progress = TelegramTestFactory.buildTextMessage("creating");
        progress.setMessageId(90);
        CommonEntity item = previewItem(bag, type);
        item.setId(UUID.fromString(original.getParent().getFileName().toString()));
        item.setStatus(Status.REVIEW);
        item.setMessage(TelegramTestFactory.buildTextMessage("original submission"));
        if (type.equals("template")) {
            bag.put(WorkflowDataKey.CREATING_TEMPLATE_MESSAGE, progress);
            bag.put(WorkflowDataKey.TEMPLATE_FILE, original);
        } else {
            bag.put(WorkflowDataKey.CREATING_SOURCE_MESSAGE, progress);
            bag.put(WorkflowDataKey.SOURCE_FILES, List.of(original));
        }
        return progress;
    }

    private CommonEntity previewItem(WorkflowDataBag bag, String type) {
        return type.equals("template") ? bag.get(WorkflowDataKey.TEMPLATE, Template.class)
            : bag.<List<Source>>getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class).getFirst();
    }

    private void stubStoredPreview(String type, CommonEntity item) {
        if (type.equals("source")) {
            when(database.findAndModify(eq(previewQuery(item.getId())),
                any(io.jsondb.query.Update.class), eq(Source.class))).thenAnswer(invocation -> {
                    applyPreviewUpdate(item, invocation.getArgument(1));
                    return (Source) item;
                });
        } else {
            when(database.findAndModify(eq(previewQuery(item.getId())),
                any(io.jsondb.query.Update.class), eq(Template.class))).thenAnswer(invocation -> {
                    applyPreviewUpdate(item, invocation.getArgument(1));
                    return (Template) item;
                });
        }
    }

    private void applyPreviewUpdate(CommonEntity item, io.jsondb.query.Update update) {
        item.setPreviewChatId((Long) update.getUpdateData().get("previewChatId"));
        item.setPreviewMessageId((Integer) update.getUpdateData().get("previewMessageId"));
    }

    private String previewQuery(UUID id) {
        return "/.[id='%s' and status='REVIEW']".formatted(id);
    }

    private void verifyPreviewUpdate(String type, UUID id, Message response) {
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<io.jsondb.query.Update> update = ArgumentCaptor.forClass(io.jsondb.query.Update.class);
        if (type.equals("source")) {
            verify(database).findAndModify(query.capture(), update.capture(), eq(Source.class));
        } else {
            verify(database).findAndModify(query.capture(), update.capture(), eq(Template.class));
        }
        assertEquals(previewQuery(id), query.getValue());
        assertEquals(Map.of("previewChatId", response.getChatId(),
            "previewMessageId", response.getMessageId()), update.getValue().getUpdateData());
        verifyNoMoreInteractions(database);
    }

    private WorkflowDataBag publicationBag(Path output) {
        Template template = new Template();
        template.setId(UUID.randomUUID());
        template.setWeight(3);
        Source reducible = new Source();
        reducible.setId(UUID.randomUUID());
        reducible.setWeight(2);
        Source atMinimum = new Source();
        atMinimum.setId(UUID.randomUUID());
        atMinimum.setWeight(1);
        WorkflowDataBag bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        bag.put(WorkflowDataKey.MEME_FILE, output);
        bag.put(WorkflowDataKey.TEMPLATE, template);
        bag.put(WorkflowDataKey.SOURCES, List.of(reducible, atMinimum));
        bag.put(WorkflowDataKey.MEMES_HISTORY, List.of());
        return bag;
    }

    private void assertWeightsUnchanged(WorkflowDataBag bag) {
        assertEquals(3, bag.get(WorkflowDataKey.TEMPLATE, Template.class).getWeight());
        List<Source> sources = bag.getGeneric(WorkflowDataKey.SOURCES, List.class, Source.class);
        assertEquals(2, sources.getFirst().getWeight());
        assertEquals(1, sources.getLast().getWeight());
    }
}