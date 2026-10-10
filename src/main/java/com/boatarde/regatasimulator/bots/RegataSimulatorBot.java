package com.boatarde.regatasimulator.bots;

import com.boatarde.regatasimulator.adapter.telegram.TelegramRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
@Slf4j
public class RegataSimulatorBot extends TelegramLongPollingBot {

    private final String username;
    private final TelegramRouter routerService;

    public RegataSimulatorBot(@Value("${telegram.bots.regata-simulator.token}") String token,
                              @Value("${telegram.bots.regata-simulator.username}") String username,
                              TelegramRouter routerService) {
        super(token);
        this.username = username;
        this.routerService = routerService;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        routerService.route(update, username);
    }
}
