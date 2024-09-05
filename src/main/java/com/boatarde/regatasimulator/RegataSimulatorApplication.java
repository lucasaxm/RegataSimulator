package com.boatarde.regatasimulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@SpringBootApplication
@Slf4j
public class RegataSimulatorApplication {

    private final RegataSimulatorBot regataSimulatorBot;

    public RegataSimulatorApplication(RegataSimulatorBot regataSimulatorBot, ObjectMapper objectMapper) {
        this.regataSimulatorBot = regataSimulatorBot;
    }

    public static void main(String[] args) {
        SpringApplication.run(RegataSimulatorApplication.class, args);
    }

    @PostConstruct
    public void onStartUpInit() {
        registerHelloBotAbilities();
    }

    private void registerHelloBotAbilities() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(regataSimulatorBot);
        } catch (TelegramApiException e) {
            log.error(String.format("Error registering bots: %s", e.getMessage()), e);
        }
    }
}
