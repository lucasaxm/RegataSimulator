package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.TelegramGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledTaskService {

    private final MemeService memes;
    private final BackupService backups;
    private final Long channelId;

    public ScheduledTaskService(MemeService memes, BackupService backups,
                                @Value("${telegram.bots.regata-simulator.channel}") Long channelId) {
        this.memes = memes; this.backups = backups; this.channelId = channelId;
    }

    @Scheduled(cron = "${regata-simulator.scheduling.publish-cron:0 0,30 * * * *}", zone = "${regata-simulator.scheduling.zone:America/Sao_Paulo}")
    public void generateMeme() {
        memes.publish(new MemeService.Publish(MemeService.Origin.SCHEDULED, TelegramGateway.Destination.chat(channelId)));
    }

    public void generateAdminMeme() {
        memes.publish(new MemeService.Publish(MemeService.Origin.ADMIN, TelegramGateway.Destination.chat(channelId)));
    }

    @Scheduled(cron = "${regata-simulator.scheduling.backup-cron:0 15 12 * * SUN}", zone = "${regata-simulator.scheduling.zone:America/Sao_Paulo}")
    public void createBackup() {
        backups.create();
    }
}
