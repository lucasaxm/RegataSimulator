package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.Optional;

import static com.boatarde.regatasimulator.util.TelegramUtils.extractCommandContent;

@Component
public class SendMemeRoute implements Route {

    private static final String MEME_COMMAND = "/meme";

    @Override
    public Optional<WorkflowAction> test(Update update, TelegramBot bot) {
        if (bot instanceof RegataSimulatorBot
            && update.hasMessage()
            && "".equals(extractCommandContent(update.getMessage(), MEME_COMMAND, bot.getBotUsername()))) {
            return Optional.of(WorkflowAction.GET_RANDOM_TEMPLATE);
        }
        return Optional.empty();
    }
}
