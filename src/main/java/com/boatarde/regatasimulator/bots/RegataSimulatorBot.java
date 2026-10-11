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
        super(boundedOptions(), token);
        this.username = username;
        this.routerService = routerService;
    }

    public static org.telegram.telegrambots.bots.DefaultBotOptions boundedOptions() {
        var options=new org.telegram.telegrambots.bots.DefaultBotOptions();
        options.setRequestConfig(org.apache.http.client.config.RequestConfig.custom()
            .setConnectTimeout(3_000).setSocketTimeout(10_000).setConnectionRequestTimeout(3_000).build());
        options.setGetUpdatesTimeout(5);
        return options;
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
