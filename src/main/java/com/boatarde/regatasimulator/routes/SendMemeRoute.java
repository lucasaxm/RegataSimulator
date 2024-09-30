package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.Optional;

import static com.boatarde.regatasimulator.util.TelegramUtils.extractCommandContent;

@Component
public class SendMemeRoute implements Route {

    private static final String MEME_COMMAND = "/meme";

    private final Long creatorId;

    public SendMemeRoute(@Value("${telegram.creator.id}") Long creatorId) {
        this.creatorId = creatorId;
    }

    @Override
    public Optional<WorkflowAction> test(Update update, TelegramBot bot) {
        if (bot instanceof RegataSimulatorBot
            && update.hasMessage()
            && "".equals(extractCommandContent(update.getMessage(), MEME_COMMAND, bot.getBotUsername()))
            && update.getMessage().isUserMessage() && update.getMessage().getChatId().equals(creatorId)) {
            return Optional.of(WorkflowAction.GET_RANDOM_TEMPLATE);
        }
        return Optional.empty();
    }
}
