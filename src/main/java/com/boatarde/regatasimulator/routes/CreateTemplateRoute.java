package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.TelegramUtils;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class CreateTemplateRoute implements Route {

    @Override
    public Optional<WorkflowAction> test(Update update, TelegramBot bot) {
        if (isValidBot(bot) && isValidUpdate(update)) {
            return Optional.of(WorkflowAction.CREATE_TEMPLATE);
        }
        return Optional.empty();
    }

    private boolean isValidBot(TelegramBot bot) {
        return bot instanceof RegataSimulatorBot;
    }

    private boolean isValidUpdate(Update update) {
        if (!update.hasMessage() || !update.getMessage().isUserMessage() || !update.getMessage().hasDocument()) {
            return false;
        }

        String caption = update.getMessage().getCaption();
        if (caption == null || caption.isEmpty()) {
            return false;
        }

        try {
            List<TemplateArea> areas = TelegramUtils.parseTemplateCsv(caption);
            return !areas.isEmpty();
        } catch (IOException e) {
            return false;
        }
    }
}
