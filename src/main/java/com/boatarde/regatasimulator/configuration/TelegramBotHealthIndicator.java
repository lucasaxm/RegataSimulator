package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Optional;

@Component
public class TelegramBotHealthIndicator implements HealthIndicator {

    private final RegataSimulatorBot bot;

    public TelegramBotHealthIndicator(RegataSimulatorBot bot) {
        this.bot = bot;
    }

    @Override
    public Health health() {
        try {
            User botUser = bot.getMe();
            if (botUser != null) {
                Health.Builder builder = Health.up();
                builder = addDetailSafely(builder, "username", botUser.getUserName());
                builder = addDetailSafely(builder, "firstName", botUser.getFirstName());
                builder = addDetailSafely(builder, "lastName", botUser.getLastName());
                builder = addDetailSafely(builder, "languageCode", botUser.getLanguageCode());
                builder = addDetailSafely(builder, "canJoinGroups", botUser.getCanJoinGroups());
                builder = addDetailSafely(builder, "canReadAllGroupMessages", botUser.getCanReadAllGroupMessages());
                builder = addDetailSafely(builder, "isBot", botUser.getIsBot());
                return builder.build();
            } else {
                return Health.down().withDetail("telegramBot", "Received null response from getMe").build();
            }
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }

    private Health.Builder addDetailSafely(Health.Builder builder, String key, Object value) {
        String detailValue = Optional.ofNullable(value).map(Object::toString).orElse("unknown");
        return builder.withDetail(key, detailValue);
    }
}
