package com.boatarde.regatasimulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import io.jsondb.JsonDBTemplate;
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
    private final JsonDBTemplate jsonDBTemplate;

    public RegataSimulatorApplication(RegataSimulatorBot regataSimulatorBot, JsonDBTemplate jsonDBTemplate) {
        this.regataSimulatorBot = regataSimulatorBot;
        this.jsonDBTemplate = jsonDBTemplate;
    }

    public static void main(String[] args) {
        SpringApplication.run(RegataSimulatorApplication.class, args);
    }

    @PostConstruct
    public void onStartUpInit() {
        registerHelloBotAbilities();
        createCollectionIfAbsent("users");
        createCollectionIfAbsent("templates");
        createCollectionIfAbsent("sources");
        createCollectionIfAbsent("memes");
    }

    private void createCollectionIfAbsent(String collectionName) {
        if (jsonDBTemplate.collectionExists(collectionName)) {
            log.info("{} collection already exists", collectionName);
        } else {
            log.info("Creating {} collection", collectionName);
            jsonDBTemplate.createCollection(collectionName);
        }
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
