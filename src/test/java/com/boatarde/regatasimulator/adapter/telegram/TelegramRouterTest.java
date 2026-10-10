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
        var original = new com.boatarde.regatasimulator.application.ApplicationFailure(
            com.boatarde.regatasimulator.application.ApplicationFailure.Kind.UNAVAILABLE, "private-error");
        doThrow(original).when(reports).send(any());
        doThrow(new IllegalStateException("reporting failure")).when(telegram).sendText(any());
        assertSame(original, assertThrows(com.boatarde.regatasimulator.application.ApplicationFailure.class, () -> router.route(update, "fixture")));
        verify(telegram).sendText(argThat(request -> !request.text().contains("private-error")));
    }

    private Update callback(String data) {
        User user = new User(); user.setId(42L); user.setUserName("fixture");
        Message message = TelegramTestFactory.buildTextMessage("preview"); message.setMessageId(333);
        PhotoSize photo = new PhotoSize(); photo.setFileId("preview-photo"); message.setPhoto(List.of(photo));
        CallbackQuery query = new CallbackQuery(); query.setId("callback"); query.setData(data); query.setFrom(user); query.setMessage(message);
        Update update = new Update(); update.setCallbackQuery(query); return update;
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"image/jpeg,source: barco", "image/png,SoUrCe: barco", "image/png,source:", "image/jpeg,SOURCE:   "})
    void documentSourceCaptionsDispatchOnlySource(String mime, String caption) {
        router.route(document(mime, caption), "fixture");
        verify(submissions).submitSource(argThat(request -> request.description().equals(caption.substring(caption.indexOf(':') + 1))
            && request.upload().author().getId() == 42L && request.upload().origin().legacyMessage() != null));
        verifyNoMoreInteractions(submissions); verifyNoInteractions(telegram, callbacks, ping, reports, backups, memes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png"})
    void validCsvDispatchesOnlyTemplateWithParsedGeometry(String mime) {
        router.route(document(mime, CSV), "fixture");
        verify(submissions).submitTemplate(argThat(request -> request.areas().size() == 1
            && request.areas().getFirst().getBottomRight().getY() == 30 && request.upload().author().getId() == 42L));
        verifyNoMoreInteractions(submissions); verifyNoInteractions(telegram, callbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/gif", "image/webp", "application/pdf", "IMAGE/PNG"})
    void unsupportedDocumentMimeNeverDispatches(String mime) {
        router.route(document(mime, "source: barco"), "fixture"); router.route(document(mime, CSV), "fixture");
        verifyNoInteractions(submissions, telegram, callbacks);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings = {"caption without prefix", " source: barco", "   ", HEADER, HEADER + "\n1,1,0", "Wrong,Header\n1,1"})
    void absentUnrelatedOrInvalidCsvCaptionsNeverDispatch(String caption) {
        router.route(document("image/png", caption), "fixture"); verifyNoInteractions(submissions, telegram, callbacks);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void malformedCsvNumberNeverEscapesOrDispatches(int field) {
        String[] fields = "1,1,0,0,20,0,20,30,0,30,0".split(","); fields[field] = "not-a-number";
        assertDoesNotThrow(() -> router.route(document("image/png", HEADER + "\n" + String.join(",", fields)), "fixture"));
        verifyNoInteractions(submissions, telegram, callbacks);
    }

    @Test
    void photosTextUnknownCommandsAndAbsentUpdatesHaveNoSubmissionEffects() {
        var upload = document("image/png", "source: barco"); upload.getMessage().setDocument(null);
        PhotoSize photo = new PhotoSize(); photo.setFileId("fixture"); upload.getMessage().setPhoto(List.of(photo));
        router.route(upload, "fixture"); router.route(new Update(), "fixture"); router.route(null, "fixture");
        router.route(TelegramTestFactory.buildTextMessageUpdate("source: barco"), "fixture");
        router.route(TelegramTestFactory.buildCommandTextMessageUpdate("/unknown"), "fixture");
        verifyNoInteractions(submissions, telegram, callbacks, ping, reports, backups, memes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ping", "/ping@fixture"})
    void pingWithoutSyntheticWorkflowUsesMessageDestination(String command) {
        var update = TelegramTestFactory.buildCommandTextMessageUpdate(command);
        router.route(update, "fixture");
        verify(ping).pong(new TelegramGateway.Destination(1234, update.getMessage().getMessageId(), null), update.getMessage().getDate());
    }

    private static final String HEADER = "Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background";
    private static final String CSV = HEADER + "\n1,1,0,0,20,0,20,30,0,30,0";
    private Update document(String mime, String caption) {
        var update = TelegramTestFactory.buildTextMessageUpdate("upload");
        var message = update.getMessage(); message.setText(null); message.setCaption(caption);
        User user = new User(); user.setId(42L); user.setFirstName("Ana"); message.setFrom(user);
        Document document = new Document(); document.setFileId("fixture"); document.setFileName("fixture.png"); document.setMimeType(mime);
        message.setDocument(document); return update;
    }
}