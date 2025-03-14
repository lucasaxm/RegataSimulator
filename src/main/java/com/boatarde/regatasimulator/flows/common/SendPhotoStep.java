package com.boatarde.regatasimulator.flows.common;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.util.TelegramUtils;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.interfaces.BotApiObject;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
@WorkflowStepRegistration(WorkflowAction.SEND_PHOTO_STEP)
public class SendPhotoStep implements WorkflowStep {

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        RegataSimulatorBot regataSimulatorBot = bag.get(WorkflowDataKey.REGATA_SIMULATOR_BOT, RegataSimulatorBot.class);
        SendPhoto photo = bag.get(WorkflowDataKey.SEND_PHOTO, SendPhoto.class);
        try {
            BotApiObject response = regataSimulatorBot.execute(photo);
            log.info("Response: {}", TelegramUtils.toJson(response, false));
        } catch (TelegramApiException e) {
            log.error(e.getLocalizedMessage(), e);
        }
        return WorkflowAction.NONE;
    }
}
