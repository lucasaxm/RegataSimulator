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
    /** Adapter-authorized identity; HTTP names and optional Telegram 64-bit identity remain distinct. */
    public record Actor(String name, Long telegramId) {
        public Actor(String name) { this(name,null); }
        public Actor { if (name == null || name.isBlank() || name.length()>200) throw new IllegalArgumentException("Administrator identity required"); }
    }
    public record Decision(ItemType type, UUID id, Status status, String reason, Actor actor) { }
    public enum Notification { SENT, SKIPPED, FAILED }
    public record Result(Status status, Notification notification) { }
    private final SourceService sources;
    private final TemplateService templates;
    private final TelegramGateway telegram;
    private final com.boatarde.regatasimulator.repository.AuditRepository audits;
    private final com.boatarde.regatasimulator.repository.MetadataUnitOfWork metadata;
    private final java.time.Clock clock;

    public ModerationService(SourceService sources, TemplateService templates, TelegramGateway telegram) {
        this(sources,templates,telegram,null,Runnable::run,java.time.Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModerationService(SourceService sources, TemplateService templates, TelegramGateway telegram,
        com.boatarde.regatasimulator.repository.AuditRepository audits,
        com.boatarde.regatasimulator.repository.MetadataUnitOfWork metadata,java.time.Clock clock) {
        this.sources = sources; this.templates = templates; this.telegram = telegram;
        this.audits=audits; this.metadata=metadata; this.clock=clock;
    }

    public Result decide(Decision request) {
        java.util.Objects.requireNonNull(request.actor(), "Administrator identity required");
        java.util.Objects.requireNonNull(request.type(), "Review item type required");
        if (request.status() != Status.APPROVED && request.status() != Status.REJECTED) {
            throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Review requires a decision");
        }
        var audit=com.boatarde.regatasimulator.models.ModerationAudit.builder().id(UUID.randomUUID())
            .itemType(request.type().name()).itemId(request.id()).actorName(request.actor().name())
            .actorTelegramId(request.actor().telegramId()).decidedAt(clock.millis()).decision(request.status()).build();
        com.boatarde.regatasimulator.repository.AuditRepository.validate(audit);
        var applied=new java.util.concurrent.atomic.AtomicReference<CommonEntity>();
        metadata.execute(() -> {
            applied.set(apply(request));
            if (audits!=null) audits.append(audit);
        });
        CommonEntity item=applied.get();
        String label=request.type()==ItemType.SOURCE ? SOURCE_LABEL : TEMPLATE_LABEL;
        Notification notification=notify(request,item,label);
        if (audits!=null) {
            try { audits.notification(audit.getId(),notification.name()); }
            catch (RuntimeException e) { log.warn("Moderation applied; audit notification outcome requires reconciliation"); }
        }
        return new Result(request.status(),notification);
    }

    private CommonEntity apply(Decision request) {
        CommonEntity item;
        if (request.type() == ItemType.SOURCE) {
            Source source = sources.getSource(request.id()).orElseThrow(() -> missing(SOURCE_LABEL, request.id()));
            ensureReview(source, SOURCE_LABEL);
            if (request.status() == Status.APPROVED) sources.approveSource(source); else sources.rejectSource(source);
            item = source;
        } else {
            Template template = templates.getTemplate(request.id()).orElseThrow(() -> missing(TEMPLATE_LABEL, request.id()));
            ensureReview(template, TEMPLATE_LABEL);
            if (request.status() == Status.APPROVED) templates.approveTemplate(template); else templates.rejectTemplate(template);
            item = template;
        }
        return item;
    }

    private Notification notify(Decision request,CommonEntity item,String label) {
        if (item.getMessage() == null || item.getMessage().getChat() == null) return Notification.SKIPPED;
        try {
            String text = request.status() == Status.APPROVED ? "✅ " + label + " aprovado!"
                : "❌ " + label + " recusado.\nMotivo: " + request.reason();
            telegram.sendText(new TelegramGateway.Text(SubmissionOrigin.from(item.getMessage()).destination(), text, false));
            return Notification.SENT;
        } catch (RuntimeException e) {
            log.warn("Moderation decision applied, but notification failed");
            return Notification.FAILED;
        }
    }

    private void ensureReview(CommonEntity item, String label) {
        if (item.getStatus() != Status.REVIEW) throw new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, label + " no longer in review");
    }
    private ApplicationFailure missing(String label, UUID id) {
        return new ApplicationFailure(ApplicationFailure.Kind.NOT_FOUND, label + " not found: " + id);
    }
}