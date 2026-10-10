package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.time.Clock;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class TelegramBotHealthIndicator implements HealthIndicator {

    private final RegataSimulatorBot bot;
    private final Clock clock;
    private final int timeoutMillis;
    private final int ttlMillis;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),r -> { Thread t=new Thread(r,"telegram-health"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.AbortPolicy());
    private long expires;
    private Health cached;

    public TelegramBotHealthIndicator(RegataSimulatorBot bot) {
        this(bot,Clock.systemUTC(),2000,15000);
    }

    @Autowired
    public TelegramBotHealthIndicator(RegataSimulatorBot bot, Clock clock,
        @Value("${regata-simulator.health.telegram-timeout-millis:2000}") int timeoutMillis,
        @Value("${regata-simulator.health.telegram-cache-millis:15000}") int ttlMillis) {
        if (timeoutMillis<50 || timeoutMillis>5000 || ttlMillis<100 || ttlMillis>300000) throw new IllegalArgumentException("Health bounds invalid");
        this.bot=bot; this.clock=clock; this.timeoutMillis=timeoutMillis; this.ttlMillis=ttlMillis;
    }

    @Override
    public synchronized Health health() {
        if (cached!=null && clock.millis()<expires) return cached;
        Future<Boolean> request=null;
        try {
            request=worker.submit(() -> bot.getMe()!=null);
            cached=Boolean.TRUE.equals(request.get(timeoutMillis,TimeUnit.MILLISECONDS)) ? Health.up().build() : Health.down().build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); cached=Health.down().build();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            cached=Health.down().build();
        } finally {
            if (request!=null) request.cancel(true);
        }
        expires=clock.millis()+ttlMillis;
        return cached;
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
