package com.boatarde.regatasimulator.adapter.telegram;

import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramRouterTest {
    private PingService ping;
    private ReportService reports;
    private BackupService backups;
    private MemeService memes;
    private SubmissionService submissions;
    private ReviewCallbackService callbacks;
    private TelegramGateway telegram;
    private TelegramRouter router;

    @BeforeEach
    void setUp() {
        ping = mock(PingService.class); reports = mock(ReportService.class); backups = mock(BackupService.class);
        memes = mock(MemeService.class); submissions = mock(SubmissionService.class); callbacks = mock(ReviewCallbackService.class);
        telegram = mock(TelegramGateway.class);
        router = new TelegramRouter(ping, reports, backups, memes, submissions, callbacks, telegram, 1234);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ping", "/report", "/backup", "/meme"})
    void commandsDispatchExactlyOnceThroughTypedServices(String command) {
        var update = TelegramTestFactory.buildCommandTextMessageUpdate(command + "@fixture");
        router.route(update, "fixture");
        var message = update.getMessage();
        var destination = new TelegramGateway.Destination(1234, message.getMessageId(), message.getMessageThreadId());
        switch (command) {
            case "/ping" -> verify(ping).pong(destination, message.getDate());
            case "/report" -> verify(reports).send(destination);
            case "/backup" -> verify(backups).create(destination);
            default -> verify(memes).publish(new MemeService.Publish(MemeService.Origin.TELEGRAM_COMMAND, destination));
        }
        verifyNoMoreInteractions(ping, reports, backups, memes, submissions, callbacks, telegram);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/report", "/backup", "/meme"})
    void administratorCommandsRequireCreatorPrivateChat(String command) {
        var update = TelegramTestFactory.buildCommandTextMessageUpdate(command);
        update.getMessage().getChat().setId(5678L);
        router.route(update, "fixture");
        update.getMessage().getChat().setId(1234L); update.getMessage().getChat().setType("group");
        router.route(update, "fixture");
        verifyNoInteractions(ping, reports, backups, memes, submissions, callbacks, telegram);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "1-1-1-1-1:source:confirm", "invalid:source:cancel",
        "00000000-0000-0000-0000-000000000001:SOURCE:confirm", "00000000-0000-0000-0000-000000000001:source:approve",
        "00000000-0000-0000-0000-000000000001:unknown:confirm", "00000000-0000-0000-0000-000000000001:source:confirm:extra"})
    void malformedCallbacksHaveOnlyGenericAcknowledgmentDispatch(String data) {
        router.route(callback(data), "fixture");
        verify(callbacks).reject("callback"); verifyNoMoreInteractions(callbacks);
        verifyNoInteractions(ping, reports, backups, memes, submissions, telegram);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source:confirm", "source:cancel", "template:confirm", "template:cancel"})
    void validCallbackParsingCarriesActorAndExactPreviewIdentity(String suffix) {
        UUID id = UUID.randomUUID();
        router.route(callback(id + ":" + suffix), "fixture");
        var parts = suffix.split(":");
        verify(callbacks).handle(new ReviewCallbackService.Request(parts[0].equals("source") ? ModerationService.ItemType.SOURCE
            : ModerationService.ItemType.TEMPLATE, parts[1].equals("confirm") ? ReviewCallbackService.Action.CONFIRM
            : ReviewCallbackService.Action.CANCEL, id, 42, 1234, 333, "preview-photo", "@fixture", "callback"));
        verifyNoMoreInteractions(callbacks);
    }

    @Test
    void missingActorOrPreviewCannotDispatchEffects() {
        var update = callback(UUID.randomUUID() + ":source:confirm");
        update.getCallbackQuery().setFrom(null); router.route(update, "fixture");
        update.getCallbackQuery().setMessage(null); router.route(update, "fixture");
        verify(callbacks, times(2)).reject("callback"); verifyNoMoreInteractions(callbacks);
    }

    @Test
    void directServiceFailureIsRedactedAndPreservedIfReportingFails() {
        var update = TelegramTestFactory.buildCommandTextMessageUpdate("/report");
        var original = new com.boatarde.regatasimulator.flows.ApplicationFailure(
            com.boatarde.regatasimulator.flows.ApplicationFailure.Kind.UNAVAILABLE, "private-error");
        doThrow(original).when(reports).send(any());
        doThrow(new IllegalStateException("reporting failure")).when(telegram).sendText(any());
        assertSame(original, assertThrows(com.boatarde.regatasimulator.flows.ApplicationFailure.class, () -> router.route(update, "fixture")));
        verify(telegram).sendText(argThat(request -> !request.text().contains("private-error")));
    }

    private Update callback(String data) {
        User user = new User(); user.setId(42L); user.setUserName("fixture");
        Message message = TelegramTestFactory.buildTextMessage("preview"); message.setMessageId(333);
        PhotoSize photo = new PhotoSize(); photo.setFileId("preview-photo"); message.setPhoto(List.of(photo));
        CallbackQuery query = new CallbackQuery(); query.setId("callback"); query.setData(data); query.setFrom(user); query.setMessage(message);
        Update update = new Update(); update.setCallbackQuery(query); return update;
    }
}