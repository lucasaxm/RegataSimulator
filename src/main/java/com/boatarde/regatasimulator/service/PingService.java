package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.TelegramGateway;
import org.springframework.stereotype.Service;
import java.time.Clock;

@Service
public class PingService {
    private final TelegramGateway telegram;
    private final Clock clock;
    public PingService(TelegramGateway telegram, Clock clock) { this.telegram = telegram; this.clock = clock; }
    public void pong(TelegramGateway.Destination destination, long originalEpochSecond) {
        telegram.sendText(new TelegramGateway.Text(destination,
            "pong! (%ds)".formatted(clock.instant().getEpochSecond() - originalEpochSecond), false));
    }
}