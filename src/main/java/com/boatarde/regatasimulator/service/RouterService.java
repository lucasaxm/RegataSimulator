package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.util.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.nio.file.Path;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowManager;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.routes.Route;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.List;
import java.util.Optional;
import com.boatarde.regatasimulator.application.TelegramGateway;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
public class RouterService {
    private static final int MAX_TRANSITIONS = 64;
    private static final String FAILURE_MESSAGE = "Não foi possível concluir a operação. Tente novamente mais tarde.";
    private final WorkflowManager workflowManager;
    private final List<Route> routes;
    private PingService ping;
    private ReportService reports;
    private BackupService backups;
    private MemeService memes;
    private SubmissionService submissions;

    public RouterService(WorkflowManager workflowManager, List<Route> routes) {
        this.workflowManager = workflowManager;
        this.routes = routes;
    }

    public RouterService(WorkflowManager workflowManager, List<Route> routes, PingService ping,
                         ReportService reports, BackupService backups) {
        this(workflowManager, routes);
        this.ping = ping; this.reports = reports; this.backups = backups;
    }

    public RouterService(WorkflowManager workflowManager, List<Route> routes, PingService ping,
                         ReportService reports, BackupService backups, MemeService memes) {
        this(workflowManager, routes, ping, reports, backups);
        this.memes = memes;
    }

    @Autowired
    public RouterService(WorkflowManager workflowManager, List<Route> routes, PingService ping,
                         ReportService reports, BackupService backups, MemeService memes, SubmissionService submissions) {
        this(workflowManager, routes, ping, reports, backups, memes);
        this.submissions = submissions;
    }

    public void route(Update update, TelegramBot bot) {
        List<WorkflowAction> matches = routes.stream().map(route -> route.test(update, bot))
            .flatMap(Optional::stream).toList();
        if (matches.size() > 1) {
            reportFailure(update, bot);
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Ambiguous route match");
        }
        if (!matches.isEmpty()) {
            WorkflowAction action = matches.getFirst();
            if (ping != null && action == WorkflowAction.BUILD_PONG_MESSAGE) {
                runDirect(update, bot, () -> ping.pong(destination(update), update.getMessage().getDate()));
            } else if (reports != null && action == WorkflowAction.SEND_REPORT_STEP) {
                runDirect(update, bot, () -> reports.send(destination(update)));
            } else if (backups != null && action == WorkflowAction.BACKUP_JSON_DB_STEP) {
                runDirect(update, bot, () -> backups.create(destination(update)));
            } else if (memes != null && action == WorkflowAction.GET_RANDOM_TEMPLATE) {
                runDirect(update, bot, () -> memes.publish(new MemeService.Publish(MemeService.Origin.TELEGRAM_COMMAND,
                    destination(update))));
            } else if (submissions != null && action == WorkflowAction.CREATE_SOURCE) {
                runDirect(update, bot, () -> submissions.submitSource(new SubmissionService.SourceSubmission(
                    update.getMessage().getCaption().substring(update.getMessage().getCaption().indexOf(':') + 1), upload(update))));
            } else if (submissions != null && action == WorkflowAction.CREATE_TEMPLATE) {
                runDirect(update, bot, () -> {
                    try {
                        submissions.submitTemplate(new SubmissionService.TemplateSubmission(
                            com.boatarde.regatasimulator.util.JsonDBUtils.parseTemplateCsv(update.getMessage().getCaption()), upload(update)));
                    } catch (java.io.IOException e) {
                        throw new ApplicationFailure(ApplicationFailure.Kind.INVALID_INPUT, "Invalid template CSV", e);
                    }
                });
            } else {
                startFlow(update, bot, action);
            }
        }
    }

    private TelegramGateway.Destination destination(Update update) {
        var message = update.getMessage();
        return new TelegramGateway.Destination(message.getChatId(), message.getMessageId(), message.getMessageThreadId());
    }

    private SubmissionService.Upload upload(Update update) {
        var message = update.getMessage();
        var author = message.getFrom();
        return new SubmissionService.Upload(message.getDocument().getFileId(), message.getDocument().getFileName(),
            com.boatarde.regatasimulator.models.Author.builder().id(author.getId()).firstName(author.getFirstName())
                .lastName(author.getLastName()).userName(author.getUserName()).build(),
            com.boatarde.regatasimulator.application.SubmissionOrigin.from(message));
    }

    private void runDirect(Update update, TelegramBot bot, Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException e) {
            reportFailure(update, bot);
            throw e instanceof ApplicationFailure known ? known
                : new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Application operation failed", e);
        }
    }

    public void startFlow(Update update, TelegramBot bot, WorkflowAction firstStep) {
        WorkflowAction workflowAction = firstStep;

        WorkflowDataBag workflowDataBag = new WorkflowDataBag();
        workflowDataBag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        workflowDataBag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);

        try {
            int transitions = 0;
            while (workflowAction != WorkflowAction.NONE) {
                if (workflowAction == null || ++transitions > MAX_TRANSITIONS) {
                    throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Invalid or cyclic workflow transition");
                }
                WorkflowStep step = workflowManager.getStepByEnum(workflowAction).orElseThrow(() ->
                    new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Missing nonterminal workflow registration"));
                workflowAction = step.run(workflowDataBag);
            }
        } catch (RuntimeException e) {
            ApplicationFailure failure = e instanceof ApplicationFailure known ? known
                : new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Workflow execution failed", e);
            log.error("Workflow {} failed ({})", firstStep, failure.getKind());
            reportFailure(update, bot);
            throw failure;
        } finally {
            FileUtils.deleteTree(workflowDataBag.get(WorkflowDataKey.RENDER_JOB_DIRECTORY, Path.class));
            var photo = workflowDataBag.get(WorkflowDataKey.SEND_PHOTO,
                org.telegram.telegrambots.meta.api.methods.send.SendPhoto.class);
            if (photo != null && photo.getPhoto() != null && photo.getPhoto().getNewMediaStream() != null) {
                try {
                    photo.getPhoto().getNewMediaStream().close();
                } catch (java.io.IOException e) {
                    log.warn("Could not close workflow photo stream");
                }
            }
        }
    }

    private void reportFailure(Update update, TelegramBot bot) {
        if (update == null || !(bot instanceof RegataSimulatorBot regataBot)) {
            return;
        }
        try {
            if (update.hasCallbackQuery() && update.getCallbackQuery().getId() != null) {
                regataBot.execute(AnswerCallbackQuery.builder().callbackQueryId(update.getCallbackQuery().getId())
                    .text(FAILURE_MESSAGE).build());
            } else if (update.hasMessage() && update.getMessage().getChat() != null) {
                regataBot.execute(SendMessage.builder().chatId(update.getMessage().getChatId())
                    .text(FAILURE_MESSAGE).build());
            }
        } catch (TelegramApiException | RuntimeException e) {
            log.warn("Could not deliver workflow failure notification");
        }
    }
}
