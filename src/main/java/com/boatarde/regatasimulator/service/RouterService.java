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

@Service
@Slf4j
public class RouterService {
    private static final int MAX_TRANSITIONS = 64;
    private static final String FAILURE_MESSAGE = "Não foi possível concluir a operação. Tente novamente mais tarde.";
    private final WorkflowManager workflowManager;
    private final List<Route> routes;

    public RouterService(WorkflowManager workflowManager, List<Route> routes) {
        this.workflowManager = workflowManager;
        this.routes = routes;
    }

    public void route(Update update, TelegramBot bot) {
        List<WorkflowAction> matches = routes.stream().map(route -> route.test(update, bot))
            .flatMap(Optional::stream).toList();
        if (matches.size() > 1) {
            reportFailure(update, bot);
            throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Ambiguous route match");
        }
        if (!matches.isEmpty()) {
            startFlow(update, bot, matches.getFirst());
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
