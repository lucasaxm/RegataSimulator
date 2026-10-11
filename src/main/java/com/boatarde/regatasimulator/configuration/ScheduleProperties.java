package com.boatarde.regatasimulator.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties("regata-simulator.scheduling")
@Validated
@Getter
@Setter
public class ScheduleProperties {
    private String zone="America/Sao_Paulo";
    private String publishCron="0 0,30 * * * *";
    private String backupCron="0 15 12 * * SUN";
    @AssertTrue(message="Valid explicit zone and six-field cron schedules required")
    public boolean isScheduleValid() {
        try {
            java.time.ZoneId.of(zone);
            return org.springframework.scheduling.support.CronExpression.isValidExpression(publishCron)
                && org.springframework.scheduling.support.CronExpression.isValidExpression(backupCron);
        } catch (RuntimeException e) { return false; }
    }
}