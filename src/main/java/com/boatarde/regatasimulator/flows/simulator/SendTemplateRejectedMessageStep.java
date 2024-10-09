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

@WorkflowStepRegistration(WorkflowAction.SEND_TEMPLATE_REJECTED_MESSAGE)
@Slf4j
public class SendTemplateRejectedMessageStep implements WorkflowStep {
    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        log.info("Sending template rejected message to user");
        Update update = bag.get(WorkflowDataKey.TELEGRAM_UPDATE, Update.class);
        RegataSimulatorBot bot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);

        String reason = update.getChannelPost().getText();
        Message originalMessage = update.getMessage();

        try {
            bot.execute(SendMessage.builder()
                .chatId(originalMessage.getChatId())
                .text("❌ Template recusado.\nMotivo: " + reason)
                .replyToMessageId(originalMessage.getMessageId())
                .allowSendingWithoutReply(true)
                .build());
        } catch (TelegramApiException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        return WorkflowAction.NONE;
    }
}
