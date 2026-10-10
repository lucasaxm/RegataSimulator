package com.boatarde.regatasimulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.configuration.TelegramBotRegistration;
import io.jsondb.JsonDBTemplate;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
    org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration.class
})
@Slf4j
public class RegataSimulatorApplication {

    private final RegataSimulatorBot regataSimulatorBot;
    private final JsonDBTemplate jsonDBTemplate;
    private final TelegramBotRegistration botRegistration;
    private final boolean registrationEnabled;

    public RegataSimulatorApplication(RegataSimulatorBot regataSimulatorBot, JsonDBTemplate jsonDBTemplate) {
        this(regataSimulatorBot, jsonDBTemplate, new TelegramBotRegistration(), true);
    }

    @Autowired
    public RegataSimulatorApplication(RegataSimulatorBot regataSimulatorBot, @Autowired(required=false) JsonDBTemplate jsonDBTemplate,
                                     TelegramBotRegistration botRegistration,
                                     @Value("${telegram.bots.regata-simulator.registration-enabled:true}")
                                     boolean registrationEnabled) {
        this.regataSimulatorBot = regataSimulatorBot;
        this.jsonDBTemplate = jsonDBTemplate;
        this.botRegistration = botRegistration;
        this.registrationEnabled = registrationEnabled;
    }

    public static void main(String[] args) {
        SpringApplication.run(RegataSimulatorApplication.class, args);
    }

    @PostConstruct
    public void onStartUpInit() {
        createCollectionIfAbsent("users");
        createCollectionIfAbsent("templates");
        createCollectionIfAbsent("sources");
        createCollectionIfAbsent("memes");
        createCollectionIfAbsent("audits");
        if (registrationEnabled) registerHelloBotAbilities();
    }

    private void createCollectionIfAbsent(String collectionName) {
        if (jsonDBTemplate == null) return;
        if (jsonDBTemplate.collectionExists(collectionName)) {
            log.info("{} collection already exists", collectionName);
        } else {
            log.info("Creating {} collection", collectionName);
            jsonDBTemplate.createCollection(collectionName);
        }
    }

    private void registerHelloBotAbilities() {
        try {
            botRegistration.register(regataSimulatorBot);
        } catch (TelegramApiException e) {
            log.error("Bot registration failed; startup refused");
            throw new IllegalStateException("Bot registration failed");
        }
    }
}
