package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class CreateTemplateRoute implements Route {

    public static final String JPEG_MIME = "image/jpeg";
    public static final String PNG_MIME = "image/png";

    @Override
    public Optional<WorkflowAction> test(Update update, TelegramBot bot) {
        if (!isValidBot(bot)) {
            return Optional.empty();
        }

        if (isValidTemplateFile(update)) {
            return Optional.of(WorkflowAction.CREATE_TEMPLATE);
        } else if (cancelButtonPressed(update)) {
            return Optional.of(WorkflowAction.DELETE_REVIEW_TEMPLATE);
        } else if (confirmButtonPressed(update)) {
            return Optional.of(WorkflowAction.CONFIRM_REVIEW_TEMPLATE);
        }
        return Optional.empty();
    }

    private boolean isValidBot(TelegramBot bot) {
        return bot instanceof RegataSimulatorBot;
    }

    private boolean isValidTemplateFile(Update update) {
        if (!update.hasMessage() || !update.getMessage().isUserMessage() || !update.getMessage().hasDocument()) {
            return false;
        }

        String mimeType = update.getMessage().getDocument().getMimeType();
        if (!JPEG_MIME.equals(mimeType) && !PNG_MIME.equals(mimeType)) {
            return false;
        }

        String caption = update.getMessage().getCaption();
        if (caption == null || caption.isEmpty()) {
            return false;
        }

        try {
            List<TemplateArea> areas = JsonDBUtils.parseTemplateCsv(caption);
            return !areas.isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean cancelButtonPressed(Update update) {
        return update.hasCallbackQuery() && update.getCallbackQuery().getData().endsWith(":template:cancel");
    }

    private boolean confirmButtonPressed(Update update) {
        return update.hasCallbackQuery() && update.getCallbackQuery().getData().endsWith(":template:confirm");
    }
}
