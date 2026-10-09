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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.InaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ReviewCallbackStepsTest {

    private static final String ADMIN_CHAT = "-9000";
    private static final UUID ITEM_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String REJECTED =
        "Não foi possível processar este botão. Ele é inválido, expirou ou não pertence a você.";

    @Mock
    private RegataSimulatorBot bot;
    @Mock
    private SourceService sourceService;
    @Mock
    private TemplateService templateService;

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void confirmationForwardsThenConsumesBindingThenRemovesKeyboardThenAcknowledges(String type) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":confirm");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        stubCompletionClearingPreviewBinding(type, item);

        assertStepReturnsNone(type, "confirm", update);

        ArgumentCaptor<SendPhoto> photo = ArgumentCaptor.forClass(SendPhoto.class);
        ArgumentCaptor<EditMessageReplyMarkup> edit = ArgumentCaptor.forClass(EditMessageReplyMarkup.class);
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        InOrder order = inOrder(sourceService, templateService, bot);
        verifyLookupInOrder(order, type);
        order.verify(bot).execute(photo.capture());
        verifyCompletionInOrder(order, type, item);
        order.verify(bot).execute(edit.capture());
        order.verify(bot).execute(answer.capture());
        verifyNoMoreInteractions(sourceService, templateService, bot);
        assertForwardedPhotoPayload(photo.getValue(), type);
        assertRemovedPreviewKeyboard(edit.getValue(), preview(update));
        assertAcknowledgement(answer.getValue(), label(type) + " enviado para aprovação.");
        assertReviewWithConsumedBinding(item);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void cancellationDeletesItemThenDeletesPreviewThenAcknowledges(String type) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":cancel");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);

        assertStepReturnsNone(type, "cancel", update);

        ArgumentCaptor<DeleteMessage> deletion = ArgumentCaptor.forClass(DeleteMessage.class);
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        InOrder order = inOrder(sourceService, templateService, bot);
        verifyLookupInOrder(order, type);
        verifyDeletionInOrder(order, type, item);
        order.verify(bot).execute(deletion.capture());
        order.verify(bot).execute(answer.capture());
        verifyNoMoreInteractions(sourceService, templateService, bot);
        assertDeletedPreview(deletion.getValue(), preview(update));
        assertAcknowledgement(answer.getValue(), label(type) + " deletado.");
        assertEquals(Status.REVIEW, item.getStatus());
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void missingItemAcknowledgesRejectionWithoutOtherEffects(String type, String action) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":" + action);
        stubMissing(type);

        assertStepReturnsNone(type, action, update);

        verifyLookupOnly(type);
        verifyRejectedAcknowledgementOnly();
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @MethodSource("invalidItemCases")
    void unauthorizedItemAcknowledgesRejectionWithoutMutations(String type, String action, String invalidCase)
        throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":" + action);
        CommonEntity item = ownReviewItem(type, update);
        invalidateItemOrOwner(item, update, invalidCase);
        stubFound(type, item);

        assertStepReturnsNone(type, action, update);

        verifyLookupOnly(type);
        verifyRejectedAcknowledgementOnly();
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @MethodSource("inaccessibleCallbackCases")
    void inaccessibleCallbackAcknowledgesRejectionBeforeLookup(String type, String action, String invalidCase)
        throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":" + action);
        invalidateCallbackEnvelope(update.getCallbackQuery(), invalidCase);

        assertStepReturnsNone(type, action, update);

        verifyNoInteractions(sourceService, templateService);
        verifyRejectedAcknowledgementOnly();
    }

    @ParameterizedTest(name = "{0} confirm: {1}")
    @MethodSource("invalidConfirmationPhotoCases")
    void confirmationRejectsInvalidPhotoWithoutConsumingBinding(String type, String invalidCase) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":confirm");
        CommonEntity item = ownReviewItem(type, update);
        invalidatePhoto(preview(update), invalidCase);
        stubFound(type, item);

        assertStepReturnsNone(type, "confirm", update);

        verifyLookupOnly(type);
        verifyRejectedAcknowledgementOnly();
        assertReviewWithIntactBinding(item, preview(update));
    }

    @ParameterizedTest(name = "{0} cancel: {1}")
    @MethodSource("invalidConfirmationPhotoCases")
    void cancellationDoesNotRequirePhoto(String type, String invalidCase) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":cancel");
        CommonEntity item = ownReviewItem(type, update);
        invalidatePhoto(preview(update), invalidCase);
        stubFound(type, item);

        assertStepReturnsNone(type, "cancel", update);

        InOrder order = inOrder(sourceService, templateService, bot);
        verifyLookupInOrder(order, type);
        verifyDeletionInOrder(order, type, item);
        order.verify(bot).execute(any(DeleteMessage.class));
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        order.verify(bot).execute(answer.capture());
        assertAcknowledgement(answer.getValue(), label(type) + " deletado.");
        verifyNoMoreInteractions(sourceService, templateService, bot);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @MethodSource("malformedCallbackDataCases")
    void malformedCallbackDataAcknowledgesRejectionBeforeLookup(String type, String action, String invalidCase)
        throws TelegramApiException {
        Update update = callback(malformedCallbackData(type, action, invalidCase));

        assertStepReturnsNone(type, action, update);

        verifyNoInteractions(sourceService, templateService);
        verifyRejectedAcknowledgementOnly();
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "template"})
    void canonicalUppercaseUuidIsAccepted(String type) throws TelegramApiException {
        Update update = callback(ITEM_ID.toString().toUpperCase(Locale.ROOT) + ":" + type + ":confirm");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        stubCompletionClearingPreviewBinding(type, item);

        assertStepReturnsNone(type, "confirm", update);

        verifyLookupAndCompletionOnly(type, item, 1);
        verifyConfirmationBotEffects(label(type) + " enviado para aprovação.");
        assertReviewWithConsumedBinding(item);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @MethodSource("missingCallbackQueryIdCases")
    void missingOrBlankCallbackQueryIdCannotAcknowledgeAndHasNoEffects(String type, String action, String invalidCase) {
        Update update = callback(ITEM_ID + ":" + type + ":" + action);
        update.getCallbackQuery().setId(switch (invalidCase) {
            case "null" -> null;
            case "empty" -> "";
            case "blank" -> " \t ";
            default -> throw new IllegalArgumentException(invalidCase);
        });

        assertStepReturnsNone(type, action, update);

        verifyNoInteractions(sourceService, templateService, bot);
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @MethodSource("absentUpdateCases")
    void absentUpdateOrCallbackSafelyHasNoEffects(String type, String action, String absentCase) {
        WorkflowDataBag dataBag = bag(switch (absentCase) {
            case "no-update" -> null;
            case "no-callback" -> new Update();
            default -> throw new IllegalArgumentException(absentCase);
        });

        assertEquals(WorkflowAction.NONE, assertDoesNotThrow(() -> step(type, action).run(dataBag)));

        verifyNoInteractions(sourceService, templateService, bot);
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void lookupFailureSafelyAcknowledgesRejection(String type, String action, CapturedOutput output) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":" + action);
        IllegalStateException failure = new IllegalStateException("review lookup unavailable");
        if (type.equals("source")) {
            when(sourceService.getSource(ITEM_ID)).thenThrow(failure);
        } else {
            when(templateService.getTemplate(ITEM_ID)).thenThrow(failure);
        }

        assertStepReturnsNone(type, action, update);

        verifyLookupOnly(type);
        verifyRejectedAcknowledgementOnly();
        assertTrue(output.getAll().contains("review lookup unavailable"));
    }

    @ParameterizedTest
    @CsvSource({"source,answer", "source,keyboard", "source,forward", "source,complete",
        "template,answer", "template,keyboard", "template,forward", "template,complete"})
    void confirmationFailuresPreserveEffectOrderAndNeverApprove(String type, String failureStage,
                                                               CapturedOutput output) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":confirm");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        stubConfirmationFailure(type, item, failureStage);

        assertStepReturnsNone(type, "confirm", update);

        InOrder order = inOrder(sourceService, templateService, bot);
        verifyLookupInOrder(order, type);
        order.verify(bot).execute(any(SendPhoto.class));
        if (failureStage.equals("forward")) {
            assertReviewWithIntactBinding(item, preview(update));
        } else {
            verifyCompletionInOrder(order, type, item);
            if (failureStage.equals("complete")) {
                assertReviewWithIntactBinding(item, preview(update));
            } else {
                assertReviewWithConsumedBinding(item);
                order.verify(bot).execute(any(EditMessageReplyMarkup.class));
            }
        }
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        order.verify(bot).execute(answer.capture());
        assertAcknowledgement(answer.getValue(), failureStage.equals("answer")
            ? label(type) + " enviado para aprovação." : REJECTED);
        verifyNoMoreInteractions(sourceService, templateService, bot);
        assertTrue(output.getAll().contains("review transport unavailable"));
    }

    @ParameterizedTest
    @CsvSource({"source,answer", "source,preview", "source,delete",
        "template,answer", "template,preview", "template,delete"})
    void cancellationFailuresSafelyAcknowledgeWithoutFurtherEffects(String type, String failureStage,
                                                                   CapturedOutput output) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":cancel");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        stubCancellationFailure(type, item, failureStage);

        assertStepReturnsNone(type, "cancel", update);

        InOrder order = inOrder(sourceService, templateService, bot);
        verifyLookupInOrder(order, type);
        verifyDeletionInOrder(order, type, item);
        if (!failureStage.equals("delete")) {
            order.verify(bot).execute(any(DeleteMessage.class));
        }
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        order.verify(bot).execute(answer.capture());
        assertAcknowledgement(answer.getValue(), failureStage.equals("answer") ? label(type) + " deletado." : REJECTED);
        verifyNoMoreInteractions(sourceService, templateService, bot);
        assertTrue(output.getAll().contains("cancel transport unavailable"));
    }

    @ParameterizedTest(name = "{0} {1}: {2}")
    @MethodSource("rejectedAcknowledgementFailureCases")
    void rejectedAcknowledgementFailureNeverEnablesUnauthorizedEffects(String type, String action, String invalidCase,
                                                                       CapturedOutput output) throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":" + action);
        CommonEntity item = ownReviewItem(type, update);
        if (invalidCase.equals("malformed-data")) {
            update.getCallbackQuery().setData("1-1-1-1-1:" + type + ":" + action);
        } else if (invalidCase.equals("lookup-failure")) {
            if (type.equals("source")) {
                when(sourceService.getSource(ITEM_ID)).thenThrow(new IllegalStateException("lookup unavailable"));
            } else {
                when(templateService.getTemplate(ITEM_ID)).thenThrow(new IllegalStateException("lookup unavailable"));
            }
        } else {
            update.getCallbackQuery().getFrom().setId(999L);
            stubFound(type, item);
        }
        doThrow(new TelegramApiException("ack transport unavailable"))
            .when(bot).execute(any(AnswerCallbackQuery.class));

        assertStepReturnsNone(type, action, update);

        if (invalidCase.equals("malformed-data")) {
            verifyNoInteractions(sourceService, templateService);
        } else {
            verifyLookupOnly(type);
        }
        verifyRejectedAcknowledgementOnly();
        assertReviewWithIntactBinding(item, preview(update));
        assertTrue(output.getAll().contains("ack transport unavailable"));
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void confirmReplayOrCancelAfterConfirmRejectsStillReviewItemWithConsumedBinding(String type, String nextAction)
        throws TelegramApiException {
        Update update = callback(ITEM_ID + ":" + type + ":confirm");
        CommonEntity item = ownReviewItem(type, update);
        stubFound(type, item);
        stubCompletionClearingPreviewBinding(type, item);

        assertStepReturnsNone(type, "confirm", update);
        assertReviewWithConsumedBinding(item);
        update.getCallbackQuery().setData(ITEM_ID + ":" + type + ":" + nextAction);
        assertStepReturnsNone(type, nextAction, update);

        verifyLookupAndCompletionOnly(type, item, 2);
        verifySingleConfirmationAndRejectedReplay(type);
        assertReviewWithConsumedBinding(item);
    }

    @ParameterizedTest
    @CsvSource({"source,confirm", "source,cancel", "template,confirm", "template,cancel"})
    void simultaneousReplayCannotForwardOrCancelDuringConfirmation(String type, String nextAction)
        throws TelegramApiException, InterruptedException, ExecutionException, TimeoutException {
        Update first = callback(ITEM_ID + ":" + type + ":confirm");
        Update replay = callback(ITEM_ID + ":" + type + ":" + nextAction);
        CommonEntity item = ownReviewItem(type, first);
        stubFound(type, item);
        stubCompletionClearingPreviewBinding(type, item);
        CountDownLatch forwarding = new CountDownLatch(1);
        CountDownLatch releaseForward = new CountDownLatch(1);
        CountDownLatch replayStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            forwarding.countDown();
            assertTrue(releaseForward.await(5, TimeUnit.SECONDS));
            return null;
        }).when(bot).execute(any(SendPhoto.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var confirming = executor.submit(() -> step(type, "confirm").run(bag(first)));
            try {
                assertTrue(forwarding.await(5, TimeUnit.SECONDS));
                var concurrent = executor.submit(() -> {
                    replayStarted.countDown();
                    return step(type, nextAction).run(bag(replay));
                });
                assertTrue(replayStarted.await(5, TimeUnit.SECONDS));
                releaseForward.countDown();
                assertEquals(WorkflowAction.NONE, confirming.get(5, TimeUnit.SECONDS));
                assertEquals(WorkflowAction.NONE, concurrent.get(5, TimeUnit.SECONDS));
            } finally {
                releaseForward.countDown();
            }
        }
        verifyLookupAndCompletionOnly(type, item, 2);
        verifySingleConfirmationAndRejectedReplay(type);
        assertReviewWithConsumedBinding(item);
    }

    private static Stream<Arguments> invalidItemCases() {
        return bothTypesAndActions("other-actor", "null-original", "null-original-from", "null-original-from-id",
            "null-original-chat", "null-original-chat-id", "approved", "rejected", "null-status",
            "missing-preview-chat", "missing-preview-message", "missing-both-bindings", "wrong-preview-chat",
            "wrong-preview-message", "mismatched-original-chat");
    }

    private static Stream<Arguments> inaccessibleCallbackCases() {
        return bothTypesAndActions("null-from", "null-from-id", "null-message", "null-chat", "null-chat-id",
            "null-message-id", "zero-message-id", "negative-message-id", "inaccessible-message");
    }

    private static Stream<Arguments> invalidConfirmationPhotoCases() {
        return Stream.of("source", "template")
            .flatMap(type -> photoCases().map(invalidCase -> Arguments.of(type, invalidCase)));
    }

    private static Stream<String> photoCases() {
        return Stream.of("null-photos", "empty-photos", "null-last-photo", "null-file-id", "empty-file-id", "blank-file-id");
    }

    private static Stream<Arguments> malformedCallbackDataCases() {
        return bothTypesAndActions("null", "empty", "blank", "malformed-uuid", "short-uuid", "short-last-group",
            "unhyphenated-uuid", "leading-space", "trailing-space", "wrong-type", "uppercase-type", "wrong-action",
            "uppercase-action", "additional-token", "trailing-token", "missing-action", "empty-uuid", "empty-type",
            "empty-action");
    }

    private static Stream<Arguments> missingCallbackQueryIdCases() {
        return bothTypesAndActions("null", "empty", "blank");
    }

    private static Stream<Arguments> absentUpdateCases() {
        return bothTypesAndActions("no-update", "no-callback");
    }

    private static Stream<Arguments> rejectedAcknowledgementFailureCases() {
        return bothTypesAndActions("malformed-data", "other-actor", "lookup-failure");
    }

    private static Stream<Arguments> bothTypesAndActions(String... cases) {
        return Stream.of("source", "template").flatMap(type -> Stream.of("confirm", "cancel")
            .flatMap(action -> Arrays.stream(cases).map(invalidCase -> Arguments.of(type, action, invalidCase))));
    }

    private static String malformedCallbackData(String type, String action, String invalidCase) {
        String suffix = ":" + type + ":" + action;
        return switch (invalidCase) {
            case "null" -> null;
            case "empty" -> "";
            case "blank" -> " \t ";
            case "malformed-uuid" -> "not-a-uuid" + suffix;
            case "short-uuid" -> "1-1-1-1-1" + suffix;
            case "short-last-group" -> "12345678-1234-1234-1234-123" + suffix;
            case "unhyphenated-uuid" -> ITEM_ID.toString().replace("-", "") + suffix;
            case "leading-space" -> " " + ITEM_ID + suffix;
            case "trailing-space" -> ITEM_ID + " " + suffix;
            case "wrong-type" -> ITEM_ID + ":" + (type.equals("source") ? "template" : "source") + ":" + action;
            case "uppercase-type" -> ITEM_ID + ":" + type.toUpperCase(Locale.ROOT) + ":" + action;
            case "wrong-action" -> ITEM_ID + ":" + type + ":" + (action.equals("confirm") ? "cancel" : "confirm");
            case "uppercase-action" -> ITEM_ID + ":" + type + ":" + action.toUpperCase(Locale.ROOT);
            case "additional-token" -> ITEM_ID + suffix + ":extra";
            case "trailing-token" -> ITEM_ID + suffix + ":";
            case "missing-action" -> ITEM_ID + ":" + type;
            case "empty-uuid" -> suffix;
            case "empty-type" -> ITEM_ID + "::" + action;
            case "empty-action" -> ITEM_ID + ":" + type + ":";
            default -> throw new IllegalArgumentException(invalidCase);
        };
    }

    private void invalidateItemOrOwner(CommonEntity item, Update update, String invalidCase) {
        switch (invalidCase) {
            case "other-actor" -> update.getCallbackQuery().getFrom().setId(999L);
            case "null-original" -> item.setMessage(null);
            case "null-original-from" -> item.getMessage().setFrom(null);
            case "null-original-from-id" -> item.getMessage().setFrom(userWithoutId());
            case "null-original-chat" -> item.getMessage().setChat(null);
            case "null-original-chat-id" -> item.getMessage().setChat(chatWithoutId());
            case "approved" -> item.setStatus(Status.APPROVED);
            case "rejected" -> item.setStatus(Status.REJECTED);
            case "null-status" -> item.setStatus(null);
            case "missing-preview-chat" -> item.setPreviewChatId(null);
            case "missing-preview-message" -> item.setPreviewMessageId(null);
            case "missing-both-bindings" -> clearPreviewBinding(item);
            case "wrong-preview-chat" -> item.setPreviewChatId(999L);
            case "wrong-preview-message" -> item.setPreviewMessageId(999);
            case "mismatched-original-chat" -> item.getMessage().getChat().setId(999L);
            default -> throw new IllegalArgumentException(invalidCase);
        }
    }

    private void invalidateCallbackEnvelope(CallbackQuery query, String invalidCase) {
        switch (invalidCase) {
            case "null-from" -> query.setFrom(null);
            case "null-from-id" -> query.setFrom(userWithoutId());
            case "null-message" -> query.setMessage(null);
            case "null-chat" -> ((Message) query.getMessage()).setChat(null);
            case "null-chat-id" -> ((Message) query.getMessage()).setChat(chatWithoutId());
            case "null-message-id" -> ((Message) query.getMessage()).setMessageId(null);
            case "zero-message-id" -> ((Message) query.getMessage()).setMessageId(0);
            case "negative-message-id" -> ((Message) query.getMessage()).setMessageId(-1);
            case "inaccessible-message" -> {
                Message preview = (Message) query.getMessage();
                query.setMessage(new InaccessibleMessage(preview.getChat(), preview.getMessageId(), 0));
            }
            default -> throw new IllegalArgumentException(invalidCase);
        }
    }

    private void invalidatePhoto(Message preview, String invalidCase) {
        switch (invalidCase) {
            case "null-photos" -> preview.setPhoto(null);
            case "empty-photos" -> preview.setPhoto(List.of());
            case "null-last-photo" -> preview.setPhoto(Arrays.asList(preview.getPhoto().getFirst(), null));
            case "null-file-id" -> preview.getPhoto().getLast().setFileId(null);
            case "empty-file-id" -> preview.getPhoto().getLast().setFileId("");
            case "blank-file-id" -> preview.getPhoto().getLast().setFileId(" \t ");
            default -> throw new IllegalArgumentException(invalidCase);
        }
    }

    private void stubConfirmationFailure(String type, CommonEntity item, String failureStage) throws TelegramApiException {
        TelegramApiException failure = new TelegramApiException("review transport unavailable");
        switch (failureStage) {
            case "forward" -> doThrow(failure).when(bot).execute(any(SendPhoto.class));
            case "answer" -> {
                stubCompletionClearingPreviewBinding(type, item);
                doReturn(null).when(bot).execute(any(SendPhoto.class));
                doReturn(null).when(bot).execute(any(EditMessageReplyMarkup.class));
                doThrow(failure).when(bot).execute(any(AnswerCallbackQuery.class));
            }
            case "keyboard" -> {
                stubCompletionClearingPreviewBinding(type, item);
                doReturn(null).when(bot).execute(any(SendPhoto.class));
                doReturn(Boolean.TRUE).when(bot).execute(any(AnswerCallbackQuery.class));
                doThrow(failure).when(bot).execute(any(EditMessageReplyMarkup.class));
            }
            case "complete" -> {
                // Binding consumption updates storage first; failed persistence leaves retry state intact.
                if (type.equals("source")) {
                    doThrow(new IllegalStateException("review transport unavailable"))
                        .when(sourceService).completePreviewReview((Source) item);
                } else {
                    doThrow(new IllegalStateException("review transport unavailable"))
                        .when(templateService).completePreviewReview((Template) item);
                }
            }
            default -> throw new IllegalArgumentException(failureStage);
        }
    }

    private void stubCancellationFailure(String type, CommonEntity item, String failureStage) throws TelegramApiException {
        TelegramApiException failure = new TelegramApiException("cancel transport unavailable");
        switch (failureStage) {
            case "answer" -> {
                doReturn(Boolean.TRUE).when(bot).execute(any(DeleteMessage.class));
                doThrow(failure).when(bot).execute(any(AnswerCallbackQuery.class));
            }
            case "preview" -> doThrow(failure).when(bot).execute(any(DeleteMessage.class));
            case "delete" -> {
                if (type.equals("source")) {
                    doThrow(new IllegalStateException("cancel transport unavailable"))
                        .when(sourceService).deleteSource((Source) item);
                } else {
                    doThrow(new IllegalStateException("cancel transport unavailable"))
                        .when(templateService).deleteTemplate((Template) item);
                }
            }
            default -> throw new IllegalArgumentException(failureStage);
        }
    }

    private void stubCompletionClearingPreviewBinding(String type, CommonEntity item) {
        if (type.equals("source")) {
            doAnswer(invocation -> {
                clearPreviewBinding(item);
                return null;
            }).when(sourceService).completePreviewReview((Source) item);
        } else {
            doAnswer(invocation -> {
                clearPreviewBinding(item);
                return null;
            }).when(templateService).completePreviewReview((Template) item);
        }
    }

    private void clearPreviewBinding(CommonEntity item) {
        item.setPreviewChatId(null);
        item.setPreviewMessageId(null);
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
        PhotoSize small = new PhotoSize();
        small.setFileId("small-photo");
        PhotoSize large = new PhotoSize();
        large.setFileId("largest-photo");
        Message preview = TelegramTestFactory.buildTextMessage("preview");
        preview.setPhoto(List.of(small, large));
        CallbackQuery callback = new CallbackQuery();
        callback.setId("callback-id");
        callback.setData(data);
        callback.setFrom(submitter());
        callback.setMessage(preview);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    private User submitter() {
        User user = new User();
        user.setId(42L);
        user.setFirstName("Ana");
        user.setUserName("marinheiro");
        user.setIsBot(false);
        return user;
    }

    private User userWithoutId() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(null);
        return user;
    }

    private Chat chatWithoutId() {
        Chat chat = mock(Chat.class);
        when(chat.getId()).thenReturn(null);
        return chat;
    }

    private CommonEntity ownReviewItem(String type, Update update) {
        CommonEntity item = type.equals("source") ? new Source() : new Template();
        item.setId(ITEM_ID);
        item.setStatus(Status.REVIEW);
        Message original = TelegramTestFactory.buildTextMessage("original submission");
        // Independent owner and chat fixtures: mutating the callback must never mutate ownership.
        User originalFrom = submitter();
        originalFrom.setId(update.getCallbackQuery().getFrom().getId());
        original.setFrom(originalFrom);
        original.getChat().setId(preview(update).getChatId());
        original.setMessageId(123);
        item.setMessage(original);
        item.setPreviewChatId(preview(update).getChatId());
        item.setPreviewMessageId(preview(update).getMessageId());
        return item;
    }

    private Message preview(Update update) {
        return (Message) update.getCallbackQuery().getMessage();
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

    private void verifyLookupInOrder(InOrder order, String type) {
        if (type.equals("source")) {
            order.verify(sourceService).getSource(ITEM_ID);
        } else {
            order.verify(templateService).getTemplate(ITEM_ID);
        }
    }

    private void verifyCompletionInOrder(InOrder order, String type, CommonEntity item) {
        if (type.equals("source")) {
            order.verify(sourceService).completePreviewReview((Source) item);
        } else {
            order.verify(templateService).completePreviewReview((Template) item);
        }
    }

    private void verifyDeletionInOrder(InOrder order, String type, CommonEntity item) {
        if (type.equals("source")) {
            order.verify(sourceService).deleteSource((Source) item);
        } else {
            order.verify(templateService).deleteTemplate((Template) item);
        }
    }

    private void verifyLookupAndCompletionOnly(String type, CommonEntity item, int lookupCount) {
        if (type.equals("source")) {
            verify(sourceService, times(lookupCount)).getSource(ITEM_ID);
            verify(sourceService).completePreviewReview((Source) item);
        } else {
            verify(templateService, times(lookupCount)).getTemplate(ITEM_ID);
            verify(templateService).completePreviewReview((Template) item);
        }
        verifyNoMoreInteractions(sourceService, templateService);
    }

    private void verifyRejectedAcknowledgementOnly() throws TelegramApiException {
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(bot).execute(answer.capture());
        assertAcknowledgement(answer.getValue(), REJECTED);
        verifyNoMoreInteractions(bot);
    }

    private void verifyConfirmationBotEffects(String acknowledgement) throws TelegramApiException {
        InOrder order = inOrder(bot);
        order.verify(bot).execute(any(SendPhoto.class));
        order.verify(bot).execute(any(EditMessageReplyMarkup.class));
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        order.verify(bot).execute(answer.capture());
        assertAcknowledgement(answer.getValue(), acknowledgement);
        verifyNoMoreInteractions(bot);
    }

    private void verifySingleConfirmationAndRejectedReplay(String type) throws TelegramApiException {
        InOrder order = inOrder(bot);
        order.verify(bot).execute(any(SendPhoto.class));
        order.verify(bot).execute(any(EditMessageReplyMarkup.class));
        ArgumentCaptor<AnswerCallbackQuery> answers = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        order.verify(bot, times(2)).execute(answers.capture());
        assertAcknowledgement(answers.getAllValues().getFirst(), label(type) + " enviado para aprovação.");
        assertAcknowledgement(answers.getAllValues().getLast(), REJECTED);
        verifyNoMoreInteractions(bot);
    }

    private void assertStepReturnsNone(String type, String action, Update update) {
        WorkflowStep workflowStep = step(type, action);
        WorkflowDataBag dataBag = bag(update);
        assertEquals(WorkflowAction.NONE, assertDoesNotThrow(() -> workflowStep.run(dataBag)));
    }

    private void assertForwardedPhotoPayload(SendPhoto photo, String type) {
        assertEquals(ADMIN_CHAT, photo.getChatId());
        assertEquals("largest-photo", photo.getPhoto().getAttachName());
        assertEquals("HTML", photo.getParseMode());
        assertEquals(label(type) + " id <code>" + ITEM_ID + "</code> aguardando aprovação.\nEnviado por @marinheiro",
            photo.getCaption());
    }

    private void assertRemovedPreviewKeyboard(EditMessageReplyMarkup edit, Message preview) {
        assertEquals(preview.getChatId().toString(), edit.getChatId());
        assertEquals(preview.getMessageId(), edit.getMessageId());
        assertNull(edit.getReplyMarkup());
    }

    private void assertDeletedPreview(DeleteMessage deletion, Message preview) {
        assertEquals(preview.getChatId().toString(), deletion.getChatId());
        assertEquals(preview.getMessageId(), deletion.getMessageId());
    }

    private void assertAcknowledgement(AnswerCallbackQuery answer, String text) {
        assertEquals("callback-id", answer.getCallbackQueryId());
        assertEquals(text, answer.getText());
    }

    private void assertReviewWithConsumedBinding(CommonEntity item) {
        assertEquals(Status.REVIEW, item.getStatus());
        assertNull(item.getPreviewChatId());
        assertNull(item.getPreviewMessageId());
    }

    private void assertReviewWithIntactBinding(CommonEntity item, Message preview) {
        assertEquals(Status.REVIEW, item.getStatus());
        assertEquals(preview.getChatId(), item.getPreviewChatId());
        assertEquals(preview.getMessageId(), item.getPreviewMessageId());
    }

    private WorkflowDataBag bag(Update update) {
        WorkflowDataBag bag = new WorkflowDataBag();
        if (update != null) {
            bag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);
        }
        bag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        return bag;
    }

    private String label(String type) {
        return type.equals("source") ? "Source" : "Template";
    }
}