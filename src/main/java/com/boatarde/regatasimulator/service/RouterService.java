package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.flows.WorkflowAction;
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
public class RouterService {
    private final WorkflowManager workflowManager;
    private final List<Route> routes;

    public RouterService(WorkflowManager workflowManager, List<Route> routes) {
        this.workflowManager = workflowManager;
        this.routes = routes;
    }

    public void route(Update update, TelegramBot bot) {
        routes.forEach(route -> route.test(update, bot)
            .ifPresent(firstStep -> startFlow(update, bot, firstStep)));
    }

    private void startFlow(Update update, TelegramBot bot, WorkflowAction firstStep) {
        WorkflowAction workflowAction = firstStep;
        Optional<WorkflowStep> nextStep;

        WorkflowDataBag workflowDataBag = new WorkflowDataBag();
        workflowDataBag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        workflowDataBag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);

        while ((nextStep = workflowManager.getStepByEnum(workflowAction)).isPresent()) {
            workflowAction = nextStep.get().run(workflowDataBag);
        }
    }
}
