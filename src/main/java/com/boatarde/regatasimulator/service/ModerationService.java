package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.SubmissionOrigin;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class ModerationService {
    private static final String SOURCE_LABEL = "Source";
    private static final String TEMPLATE_LABEL = "Template";
    public enum ItemType { SOURCE, TEMPLATE }
    /** Adapter-authorized administrator identity, not submitter confirmation. Durable audit is Phase 5. */
    public record Actor(String name) {
        public Actor { if (name == null || name.isBlank()) throw new IllegalArgumentException("Administrator identity required"); }
    }
    public record Decision(ItemType type, UUID id, Status status, String reason, Actor actor) { }
    public enum Notification { SENT, SKIPPED, FAILED }
    public record Result(Status status, Notification notification) { }
    private final SourceService sources;
    private final TemplateService templates;
    private final TelegramGateway telegram;

    public ModerationService(SourceService sources, TemplateService templates, TelegramGateway telegram) {
        this.sources = sources; this.templates = templates; this.telegram = telegram;
    }

    public Result decide(Decision request) {
        java.util.Objects.requireNonNull(request.actor(), "Administrator identity required");
        java.util.Objects.requireNonNull(request.type(), "Review item type required");
        if (request.status() != Status.APPROVED && request.status() != Status.REJECTED) {
            throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Review requires a decision");
        }
        CommonEntity item;
        String label;
        if (request.type() == ItemType.SOURCE) {
            Source source = sources.getSource(request.id()).orElseThrow(() -> missing(SOURCE_LABEL, request.id()));
            ensureReview(source, SOURCE_LABEL);
            if (request.status() == Status.APPROVED) sources.approveSource(source); else sources.rejectSource(source);
            item = source; label = SOURCE_LABEL;
        } else {
            Template template = templates.getTemplate(request.id()).orElseThrow(() -> missing(TEMPLATE_LABEL, request.id()));
            ensureReview(template, TEMPLATE_LABEL);
            if (request.status() == Status.APPROVED) templates.approveTemplate(template); else templates.rejectTemplate(template);
            item = template; label = TEMPLATE_LABEL;
        }
        if (item.getMessage() == null || item.getMessage().getChat() == null) return new Result(request.status(), Notification.SKIPPED);
        try {
            String text = request.status() == Status.APPROVED ? "✅ " + label + " aprovado!"
                : "❌ " + label + " recusado.\nMotivo: " + request.reason();
            telegram.sendText(new TelegramGateway.Text(SubmissionOrigin.from(item.getMessage()).destination(), text, false));
            return new Result(request.status(), Notification.SENT);
        } catch (RuntimeException e) {
            log.warn("Moderation decision applied, but notification failed");
            return new Result(request.status(), Notification.FAILED);
        }
    }

    private void ensureReview(CommonEntity item, String label) {
        if (item.getStatus() != Status.REVIEW) throw new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, label + " no longer in review");
    }
    private ApplicationFailure missing(String label, UUID id) {
        return new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, label + " not found: " + id);
    }
}