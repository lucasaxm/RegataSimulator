package com.boatarde.regatasimulator.adapter.telegram;

import com.boatarde.regatasimulator.application.SubmissionOrigin;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.service.*;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

@Component
@Slf4j
public class TelegramRouter {
    private static final String FAILURE = "Não foi possível concluir a operação. Tente novamente mais tarde.";
    private final PingService ping;
    private final ReportService reports;
    private final BackupService backups;
    private final MemeService memes;
    private final SubmissionService submissions;
    private final ReviewCallbackService callbacks;
    private final TelegramGateway telegram;
    private final long creatorId;

    public TelegramRouter(PingService ping, ReportService reports, BackupService backups, MemeService memes,
                          SubmissionService submissions, ReviewCallbackService callbacks, TelegramGateway telegram,
                          @Value("${telegram.creator.id}") long creatorId) {
        this.ping = ping; this.reports = reports; this.backups = backups; this.memes = memes;
        this.submissions = submissions; this.callbacks = callbacks; this.telegram = telegram; this.creatorId = creatorId;
    }

    public void route(Update update, String botUsername) {
        if (update == null) return;
        if (update.hasCallbackQuery()) { callback(update); return; }
        if (!update.hasMessage()) return;
        Message message = update.getMessage();
        if (message.getChat() == null || message.getChatId() == null) return;
        var destination = SubmissionOrigin.from(message).destination();
        try {
            dispatch(message, botUsername, destination);
        } catch (RuntimeException e) {
            try { telegram.sendText(new TelegramGateway.Text(destination, FAILURE, false)); }
            catch (RuntimeException ignored) { log.warn("Could not report application failure"); }
            throw e instanceof ApplicationFailure known ? known
                : new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Telegram request failed", e);
        }
    }

    private void dispatch(Message message, String botUsername, TelegramGateway.Destination destination) {
        if ("".equals(TelegramUtils.extractCommandContent(message, "/ping", botUsername))) {
            ping.pong(destination, message.getDate());
            return;
        }
        if (message.isUserMessage() && message.getChatId() == creatorId && adminCommand(message, botUsername, destination)) return;
        if (message.isUserMessage() && message.hasDocument()
            && ("image/jpeg".equals(message.getDocument().getMimeType()) || "image/png".equals(message.getDocument().getMimeType()))) {
            upload(message);
        }
    }

    private boolean adminCommand(Message message, String botUsername, TelegramGateway.Destination destination) {
        if ("".equals(TelegramUtils.extractCommandContent(message, "/report", botUsername))) reports.send(destination);
        else if ("".equals(TelegramUtils.extractCommandContent(message, "/backup", botUsername))) backups.create(destination);
        else if ("".equals(TelegramUtils.extractCommandContent(message, "/meme", botUsername))) {
            memes.publish(new MemeService.Publish(MemeService.Origin.TELEGRAM_COMMAND, destination));
        } else return false;
        return true;
    }

    private void upload(Message message) {
        String caption = message.getCaption();
        if (caption == null) return;
        var from = message.getFrom();
        if (from == null || from.getId() == null) throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Submitter absent");
        var upload = new SubmissionService.Upload(message.getDocument().getFileId(), message.getDocument().getFileName(),
            Author.builder().id(from.getId()).firstName(from.getFirstName()).lastName(from.getLastName()).userName(from.getUserName()).build(),
            SubmissionOrigin.from(message));
        if (caption.toLowerCase(Locale.ROOT).startsWith("source:")) {
            submissions.submitSource(new SubmissionService.SourceSubmission(caption.substring(caption.indexOf(':') + 1), upload));
        } else {
            try { submissions.submitTemplate(new SubmissionService.TemplateSubmission(JsonDBUtils.parseTemplateCsv(caption), upload)); }
            catch (IOException ignored) { /* Existing routing ignores invalid template captions. */ }
        }
    }

    private void callback(Update update) {
        var query = update.getCallbackQuery();
        try {
            String[] parts = query.getData() == null ? new String[0] : query.getData().split(":", -1);
            if (parts.length != 3 || query.getFrom() == null || query.getFrom().getId() == null
                || !(query.getMessage() instanceof Message preview) || preview.getChat() == null
                || preview.getChatId() == null || preview.getMessageId() == null || preview.getMessageId() <= 0) {
                callbacks.reject(query.getId()); return;
            }
            UUID id = UUID.fromString(parts[0]);
            if (!id.toString().equalsIgnoreCase(parts[0])) { callbacks.reject(query.getId()); return; }
            var type = switch (parts[1]) { case "source" -> ModerationService.ItemType.SOURCE;
                case "template" -> ModerationService.ItemType.TEMPLATE; default -> throw new IllegalArgumentException(); };
            var action = switch (parts[2]) { case "confirm" -> ReviewCallbackService.Action.CONFIRM;
                case "cancel" -> ReviewCallbackService.Action.CANCEL; default -> throw new IllegalArgumentException(); };
            String fileId = preview.getPhoto() == null || preview.getPhoto().isEmpty() || preview.getPhoto().getLast() == null
                ? null : preview.getPhoto().getLast().getFileId();
            callbacks.handle(new ReviewCallbackService.Request(type, action, id, query.getFrom().getId(), preview.getChatId(),
                preview.getMessageId(), fileId, TelegramUtils.usernameOrFullName(query.getFrom()), query.getId()));
        } catch (RuntimeException e) { callbacks.reject(query.getId()); }
    }
}