package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.TemplateService;
import com.boatarde.regatasimulator.util.TelegramUtils;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ReviewCallbackStepsTest {

    private static final String ADMIN_CHAT = "-9000";
    private static final UUID ITEM_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");

    @Mock
    private RegataSimulatorBot bot;
    @Mock
    private SourceService sourceService;
    @Mock
    private TemplateService templateService;

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void confirmationOnlyForwardsForApprovalAndRemovesKeyboard(String type) throws Exception {
        Update update = callback(ITEM_ID + ":" + type + ":confirm");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubCallbackUtilities(telegram, update);

            assertEquals(WorkflowAction.NONE, step(type, "confirm").run(bag(update)));

            ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            ArgumentCaptor<EditMessageReplyMarkup> edit = ArgumentCaptor.forClass(EditMessageReplyMarkup.class);
            ArgumentCaptor<SendPhoto> photo = ArgumentCaptor.forClass(SendPhoto.class);
            InOrder order = inOrder(bot);
            order.verify(bot).execute(answer.capture());
            order.verify(bot).execute(edit.capture());
            order.verify(bot).execute(photo.capture());
            order.verifyNoMoreInteractions();

            assertEquals("callback-id", answer.getValue().getCallbackQueryId());
            assertEquals(label(type) + " enviado para aprovação.", answer.getValue().getText());
            assertEquals(update.getCallbackQuery().getMessage().getChatId().toString(), edit.getValue().getChatId());
            assertEquals(update.getCallbackQuery().getMessage().getMessageId(), edit.getValue().getMessageId());
            assertNull(edit.getValue().getReplyMarkup());
            assertEquals(ADMIN_CHAT, photo.getValue().getChatId());
            assertEquals("largest-photo", photo.getValue().getPhoto().getAttachName());
            assertEquals("HTML", photo.getValue().getParseMode());
            assertTrue(photo.getValue().getCaption().contains(ITEM_ID.toString()));
            assertTrue(photo.getValue().getCaption().contains("aguardando aprovação"));
            assertTrue(photo.getValue().getCaption().contains("@marinheiro"));
            assertEquals(Status.REVIEW, item.getStatus());
            // Submitter confirmation is not administrator approval; neither service may mutate the item.
            verifyLookupOnly(type);
            telegram.verify(() -> TelegramUtils.extractItemId(update.getCallbackQuery().getData()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void cancelDeletesKnownOwnReviewItemThenAcknowledgesAndDeletesPreview(String type) throws Exception {
        Update update = callback(ITEM_ID + ":" + type + ":cancel");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        assertEquals(Status.REVIEW, item.getStatus());
        assertEquals(item.getMessage().getFrom().getId(), update.getCallbackQuery().getFrom().getId());
        // Phase1 authorization/status enforcement is intentionally not characterized as permissive.

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubCallbackUtilities(telegram, update);

            assertEquals(WorkflowAction.NONE, step(type, "cancel").run(bag(update)));

            ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
            ArgumentCaptor<DeleteMessage> deletion = ArgumentCaptor.forClass(DeleteMessage.class);
            InOrder order = inOrder(sourceService, templateService, bot);
            if (type.equals("source")) {
                order.verify(sourceService).getSource(ITEM_ID);
                order.verify(sourceService).deleteSource((Source) item);
            } else {
                order.verify(templateService).getTemplate(ITEM_ID);
                order.verify(templateService).deleteTemplate((Template) item);
            }
            order.verify(bot).execute(answer.capture());
            order.verify(bot).execute(deletion.capture());
            order.verifyNoMoreInteractions();
            verifyNoMoreInteractions(sourceService, templateService, bot);

            assertEquals("callback-id", answer.getValue().getCallbackQueryId());
            assertEquals(label(type) + " deletado.", answer.getValue().getText());
            assertEquals(update.getCallbackQuery().getMessage().getChatId().toString(), deletion.getValue().getChatId());
            assertEquals(update.getCallbackQuery().getMessage().getMessageId(), deletion.getValue().getMessageId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void currentlyCancelMissingItemStopsWithoutAcknowledgingCallback(String type) {
        // Phase1: provide an idempotent user-visible response when an item is already absent.
        Update update = callback(ITEM_ID + ":" + type + ":cancel");
        stubMissing(type);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubCallbackUtilities(telegram, update);

            assertEquals(WorkflowAction.NONE, step(type, "cancel").run(bag(update)));
            verifyLookupOnly(type);
            verifyNoInteractions(bot);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void currentlyConfirmMissingItemThrowsInsteadOfAcknowledgingCallback(String type) {
        // Phase1: translate missing-item failures into a safe callback result.
        Update update = callback(ITEM_ID + ":" + type + ":confirm");
        stubMissing(type);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubCallbackUtilities(telegram, update);

            WorkflowStep workflowStep = step(type, "confirm");
            WorkflowDataBag dataBag = bag(update);
            assertThrows(IllegalArgumentException.class, () -> workflowStep.run(dataBag));
            verifyLookupOnly(type);
            verifyNoInteractions(bot);
        }
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void currentlyMalformedCallbackUuidThrowsBeforeLookupOrAcknowledgement(String type, String action) {
        // Phase1: validate callback identifiers at the adapter boundary.
        Update update = callback("not-a-uuid:" + type + ":" + action);

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            telegram.when(() -> TelegramUtils.extractItemId(update.getCallbackQuery().getData())).thenCallRealMethod();

            WorkflowStep workflowStep = step(type, action);
            WorkflowDataBag dataBag = bag(update);
            assertThrows(IllegalArgumentException.class, () -> workflowStep.run(dataBag));
            verifyNoInteractions(sourceService, templateService, bot);
        }
    }

    @ParameterizedTest
    @CsvSource({"source,answer", "source,keyboard", "source,forward",
        "template,answer", "template,keyboard", "template,forward"})
    void characterizesConfirmationTelegramErrorsAsLoggedAndSwallowed(String type, String failureStage,
                                                                    CapturedOutput output) throws Exception {
        // Phase1: make partial acknowledgement/forwarding failure recoverable without approving the item.
        Update update = callback(ITEM_ID + ":" + type + ":confirm");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        TelegramApiException failure = new TelegramApiException("review transport unavailable");
        switch (failureStage) {
            case "answer" -> doThrow(failure).when(bot).execute(any(AnswerCallbackQuery.class));
            case "keyboard" -> {
                doReturn(Boolean.TRUE).when(bot).execute(any(AnswerCallbackQuery.class));
                doThrow(failure).when(bot).execute(any(EditMessageReplyMarkup.class));
            }
            case "forward" -> {
                doReturn(Boolean.TRUE).when(bot).execute(any(AnswerCallbackQuery.class));
                doReturn(null).when(bot).execute(any(EditMessageReplyMarkup.class));
                doThrow(failure).when(bot).execute(any(SendPhoto.class));
            }
            default -> throw new IllegalArgumentException(failureStage);
        }

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubCallbackUtilities(telegram, update);

            WorkflowStep workflowStep = step(type, "confirm");
            WorkflowDataBag dataBag = bag(update);
            assertEquals(WorkflowAction.NONE, assertDoesNotThrow(() -> workflowStep.run(dataBag)));
            assertEquals(Status.REVIEW, item.getStatus());
            verifyLookupOnly(type);
            verify(bot).execute(any(AnswerCallbackQuery.class));
            if (failureStage.equals("answer")) {
                verify(bot, never()).execute(any(EditMessageReplyMarkup.class));
                verify(bot, never()).execute(any(SendPhoto.class));
            } else {
                verify(bot).execute(any(EditMessageReplyMarkup.class));
                verify(bot, failureStage.equals("forward") ? times(1) : never()).execute(any(SendPhoto.class));
            }
            verifyNoMoreInteractions(bot);
            assertTrue(output.getAll().contains("review transport unavailable"));
        }
    }

    @ParameterizedTest
    @CsvSource({"source,answer", "source,preview", "template,answer", "template,preview"})
    void characterizesCancellationTelegramErrorsAfterOwnReviewItemDeletion(String type, String failureStage,
                                                                          CapturedOutput output) throws Exception {
        // Phase1: a Telegram failure does not roll back the prior storage deletion.
        Update update = callback(ITEM_ID + ":" + type + ":cancel");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        TelegramApiException failure = new TelegramApiException("cancel transport unavailable");
        if (failureStage.equals("answer")) {
            doThrow(failure).when(bot).execute(any(AnswerCallbackQuery.class));
        } else {
            doReturn(Boolean.TRUE).when(bot).execute(any(AnswerCallbackQuery.class));
            doThrow(failure).when(bot).execute(any(DeleteMessage.class));
        }

        try (MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class)) {
            stubCallbackUtilities(telegram, update);

            WorkflowStep workflowStep = step(type, "cancel");
            WorkflowDataBag dataBag = bag(update);
            assertEquals(WorkflowAction.NONE, assertDoesNotThrow(() -> workflowStep.run(dataBag)));
            if (type.equals("source")) {
                verify(sourceService).getSource(ITEM_ID);
                verify(sourceService).deleteSource((Source) item);
            } else {
                verify(templateService).getTemplate(ITEM_ID);
                verify(templateService).deleteTemplate((Template) item);
            }
            verify(bot).execute(any(AnswerCallbackQuery.class));
            verify(bot, failureStage.equals("preview") ? times(1) : never()).execute(any(DeleteMessage.class));
            verifyNoMoreInteractions(sourceService, templateService, bot);
            assertTrue(output.getAll().contains("cancel transport unavailable"));
        }
    }

    private WorkflowStep step(String type, String action) {
        if (type.equals("source")) {
            return action.equals("confirm") ? new ConfirmReviewSourceStep(ADMIN_CHAT, sourceService)
                : new DeleteReviewSourceStep(sourceService);
        }
        return action.equals("confirm") ? new ConfirmReviewTemplateStep(ADMIN_CHAT, templateService)
            : new DeleteReviewTemplateStep(templateService);
    }

    private Update callback(String data) {
        User user = new User();
        user.setId(42L);
        user.setFirstName("Ana");
        user.setUserName("marinheiro");
        user.setIsBot(false);
        PhotoSize small = new PhotoSize();
        small.setFileId("small-photo");
        PhotoSize large = new PhotoSize();
        large.setFileId("largest-photo");
        Message preview = TelegramTestFactory.buildTextMessage("preview");
        preview.setPhoto(List.of(small, large));
        CallbackQuery callback = new CallbackQuery();
        callback.setId("callback-id");
        callback.setData(data);
        callback.setFrom(user);
        callback.setMessage(preview);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    private CommonEntity ownReviewItem(String type, Update update) {
        CommonEntity item = type.equals("source") ? new Source() : new Template();
        item.setId(ITEM_ID);
        item.setStatus(Status.REVIEW);
        Message original = TelegramTestFactory.buildTextMessage("original submission");
        original.setFrom(update.getCallbackQuery().getFrom());
        item.setMessage(original);
        return item;
    }

    private void stubFound(String type, CommonEntity item) {
        if (type.equals("source")) {
            when(sourceService.getSource(ITEM_ID)).thenReturn(Optional.of((Source) item));
        } else {
            when(templateService.getTemplate(ITEM_ID)).thenReturn(Optional.of((Template) item));
        }
    }

    private void stubMissing(String type) {
        if (type.equals("source")) {
            when(sourceService.getSource(ITEM_ID)).thenReturn(Optional.empty());
        } else {
            when(templateService.getTemplate(ITEM_ID)).thenReturn(Optional.empty());
        }
    }

    private void verifyLookupOnly(String type) {
        if (type.equals("source")) {
            verify(sourceService).getSource(ITEM_ID);
        } else {
            verify(templateService).getTemplate(ITEM_ID);
        }
        verifyNoMoreInteractions(sourceService, templateService);
    }

    private void stubCallbackUtilities(MockedStatic<TelegramUtils> telegram, Update update) {
        telegram.when(() -> TelegramUtils.extractItemId(update.getCallbackQuery().getData())).thenReturn(ITEM_ID);
        telegram.when(() -> TelegramUtils.usernameOrFullName(update.getCallbackQuery().getFrom()))
            .thenReturn("@marinheiro");
    }

    private WorkflowDataBag bag(Update update) {
        WorkflowDataBag bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);
        bag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        return bag;
    }

    private String label(String type) {
        return type.equals("source") ? "Source" : "Template";
    }
}