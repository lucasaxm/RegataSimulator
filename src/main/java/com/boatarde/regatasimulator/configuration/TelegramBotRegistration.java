package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Component
public class TelegramBotRegistration {

    public void register(RegataSimulatorBot bot) throws TelegramApiException {
        new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
    }
}