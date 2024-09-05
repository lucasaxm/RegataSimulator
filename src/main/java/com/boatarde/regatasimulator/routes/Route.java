package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.Optional;

public interface Route {
    Optional<WorkflowAction> test(Update update, TelegramBot bot);
}
