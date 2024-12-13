package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.SEND_SOURCE_APPROVED_MESSAGE_STEP)
public class SendSourceApprovedMessageStep implements WorkflowStep {

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        log.info("Sending source approved message to user");
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        Message originalMessage = update.getMessage();

        try {
            bot.execute(SendMessage.builder()
                .chatId(originalMessage.getChatId())
                .text("✅ Source aprovado!")
                .replyToMessageId(originalMessage.getMessageId())
                .allowSendingWithoutReply(true)
                .build());
        } catch (TelegramApiException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        return WorkflowAction.NONE;
    }
}
