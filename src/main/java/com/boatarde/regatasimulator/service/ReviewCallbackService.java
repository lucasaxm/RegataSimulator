package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.stream.IntStream;

@Service
@Slf4j
public class ReviewCallbackService {
    public enum Action { CONFIRM, CANCEL }
    public record Request(ModerationService.ItemType type, Action action, UUID itemId, long actorId,
                          long previewChatId, int previewMessageId, String photoFileId,
                          String actorDisplay, String callbackId) { }
    public static final String REJECTED = "Não foi possível processar este botão. Ele é inválido, expirou ou não pertence a você.";
    private static final Object[] LOCKS = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final SourceService sources;
    private final TemplateService templates;
    private final TelegramGateway telegram;
    private final long adminChatId;

    public ReviewCallbackService(SourceService sources, TemplateService templates, TelegramGateway telegram,
                                 @Value("${telegram.creator.id}") long adminChatId) {
        this.sources = sources; this.templates = templates; this.telegram = telegram; this.adminChatId = adminChatId;
    }

    public void reject(String callbackId) { acknowledge(callbackId, REJECTED); }

    public void handle(Request request) {
        if (request == null || request.itemId() == null || request.type() == null || request.action() == null
            || request.callbackId() == null || request.callbackId().isBlank() || request.previewMessageId() <= 0) {
            reject(request == null ? null : request.callbackId());
            return;
        }
        synchronized (LOCKS[Math.floorMod(request.itemId().hashCode(), LOCKS.length)]) {
            handleLocked(request);
        }
    }

    private void handleLocked(Request request) {
        String label = request.type() == ModerationService.ItemType.SOURCE ? "Source" : "Template";
        try {
            CommonEntity item = request.type() == ModerationService.ItemType.SOURCE
                ? sources.getSource(request.itemId()).orElse(null) : templates.getTemplate(request.itemId()).orElse(null);
            if (!authorized(item, request) || !usablePhoto(request)) {
                reject(request.callbackId());
                return;
            }
            if (request.action() == Action.CONFIRM) {
                telegram.forwardPreview(adminChatId, request.photoFileId(),
                    "%s id <code>%s</code> aguardando aprovação.%nEnviado por %s".formatted(label, item.getId(), request.actorDisplay()));
                if (item instanceof Source source) sources.completePreviewReview(source);
                else templates.completePreviewReview((Template) item);
                telegram.clearKeyboard(request.previewChatId(), request.previewMessageId());
            } else {
                if (item instanceof Source source) sources.deleteSource(source); else templates.deleteTemplate((Template) item);
                telegram.deleteMessage(request.previewChatId(), request.previewMessageId());
            }
        } catch (RuntimeException e) {
            log.warn("Review callback failed; acknowledgement remains generic");
            reject(request.callbackId());
            return;
        }
        acknowledge(request.callbackId(), label + (request.action() == Action.CONFIRM ? " enviado para aprovação." : " deletado."));
    }

    private boolean authorized(CommonEntity item, Request request) {
        if (item == null || item.getStatus() != Status.REVIEW) return false;
        var original = item.getMessage();
        return original != null && original.getFrom() != null && original.getFrom().getId() != null
            && original.getFrom().getId() == request.actorId() && original.getChat() != null
            && original.getChatId() != null && original.getChatId() == request.previewChatId()
            && item.getPreviewChatId() != null && item.getPreviewChatId() == request.previewChatId()
            && item.getPreviewMessageId() != null && item.getPreviewMessageId() == request.previewMessageId();
    }

    private boolean usablePhoto(Request request) {
        return request.action() != Action.CONFIRM || request.photoFileId() != null && !request.photoFileId().isBlank();
    }

    private void acknowledge(String id, String text) {
        if (id == null || id.isBlank()) return;
        try { telegram.acknowledge(id, text); }
        catch (RuntimeException e) { log.warn("Could not acknowledge review callback"); }
    }
}