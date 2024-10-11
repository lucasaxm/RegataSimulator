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
public class BackupJsonDBRoute implements Route {
    private static final String BACKUP_COMMAND = "/backup";

    private final Long creatorId;

    public BackupJsonDBRoute(@Value("${telegram.creator.id}") Long creatorId) {
        this.creatorId = creatorId;
    }

    @Override
    public Optional<WorkflowAction> test(Update update, TelegramBot bot) {
        if (bot instanceof RegataSimulatorBot
            && update.hasMessage()
            && "".equals(extractCommandContent(update.getMessage(), BACKUP_COMMAND, bot.getBotUsername()))
            && update.getMessage().isUserMessage() && update.getMessage().getChatId().equals(creatorId)) {
            return Optional.of(WorkflowAction.BACKUP_JSON_DB_STEP);
        }
        return Optional.empty();
    }
}
