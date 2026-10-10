package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.telegram.telegrambots.meta.api.objects.User;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationalHealthTest {
    @Test void externalHealthIsCachedAndDetailsCannotLeakBotIdentityOrException() throws Exception {
        var bot=mock(RegataSimulatorBot.class);
        when(bot.getMe()).thenReturn(new User());
        var health=new TelegramBotHealthIndicator(bot,Clock.systemUTC(),100,10000);
        try {
            assertEquals(Status.UP,health.health().getStatus()); assertTrue(health.health().getDetails().isEmpty());
            verify(bot,times(1)).getMe();
        } finally { health.close(); }
    }
    @Test void uninterruptibleExternalCallIsBoundedAndCannotCreateMoreWorkers() throws Exception {
        var bot=mock(RegataSimulatorBot.class);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(bot.getMe()).thenAnswer(call -> {
            entered.countDown(); boolean done=false;
            while(!done) { try { release.await(); done=true; } catch(InterruptedException e) { /* emulate transport ignoring cancellation */ } }
            return new User();
        });
        var health=new TelegramBotHealthIndicator(bot,Clock.systemUTC(),50,100);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(1),()->assertEquals(Status.DOWN,health.health().getStatus()));
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertTrue(health.health().getDetails().isEmpty()); verify(bot,times(1)).getMe();
        } finally { release.countDown(); health.close(); }
    }
    @Test void actualSdkOptionsBoundConnectReadPoolAndLongPolling() {
        var options=RegataSimulatorBot.boundedOptions();
        assertEquals(3000,options.getRequestConfig().getConnectTimeout());
        assertEquals(10000,options.getRequestConfig().getSocketTimeout());
        assertEquals(3000,options.getRequestConfig().getConnectionRequestTimeout()); assertEquals(5,options.getGetUpdatesTimeout());
    }
    @Test void scheduleZoneAndCronRejectInvalidValues() {
        var properties=new ScheduleProperties(); assertTrue(properties.isScheduleValid());
        properties.setZone("not/a-zone"); assertFalse(properties.isScheduleValid());
        properties.setZone("UTC"); properties.setPublishCron("invalid"); assertFalse(properties.isScheduleValid());
    }
}