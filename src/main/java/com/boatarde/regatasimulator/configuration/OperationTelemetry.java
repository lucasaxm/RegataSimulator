package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.service.ModerationService;
import com.boatarde.regatasimulator.service.SourceImporterService;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** No arguments, raw exception messages, actors, paths or Telegram URLs enter telemetry. */
@Aspect
@Component
@Order(-10)
public class OperationTelemetry {
    private static final Set<String> SERVICES=Set.of("MemeService","SubmissionService","ModerationService","ReviewCallbackService",
        "SourceImporterService","SourceService","TemplateService","BackupService","PingService","ReportService","RecoverySnapshotService");
    private static final ThreadLocal<String> CURRENT=new ThreadLocal<>();
    private final MeterRegistry registry;
    public OperationTelemetry(MeterRegistry registry) { this.registry=registry; }

    @Around("execution(public * com.boatarde.regatasimulator.service.*Service.*(..)) || execution(public * com.boatarde.regatasimulator.migration.RecoverySnapshotService.capture(..))")
    public Object invoke(ProceedingJoinPoint call) throws Throwable {
        String service=call.getSignature().getDeclaringType().getSimpleName();
        String operation=SERVICES.contains(service) ? service+"."+call.getSignature().getName() : "other";
        boolean outer=CURRENT.get()==null;
        String previous=MDC.get("operationId");
        if (outer) CURRENT.set(UUID.randomUUID().toString());
        MDC.put("operationId",CURRENT.get());
        long start=System.nanoTime();
        String outcome="completed";
        try {
            Object result=call.proceed();
            if (result instanceof ModerationService.Result moderation && moderation.notification()==ModerationService.Notification.FAILED) outcome="notification_failed";
            if (result instanceof SourceImporterService.ImportReport report && (report.persistenceFailed() || report.rows().stream().anyMatch(r -> r.outcome()==SourceImporterService.Outcome.FAILED))) outcome="partial_import";
            return result;
        } catch (ApplicationFailure e) { outcome=e.getKind().name().toLowerCase(java.util.Locale.ROOT); throw e; }
        catch (Throwable e) { outcome="failed"; throw e; }
        finally {
            long duration=System.nanoTime()-start;
            registry.timer("regata.operation.duration","operation",operation,"outcome",outcome).record(duration,TimeUnit.NANOSECONDS);
            registry.counter("regata.operation.count","operation",operation,"outcome",outcome).increment();
            LoggerFactory.getLogger(OperationTelemetry.class).info("operationId={} operation={} outcome={} durationMs={}",CURRENT.get(),operation,outcome,TimeUnit.NANOSECONDS.toMillis(duration));
            if (previous==null) MDC.remove("operationId"); else MDC.put("operationId",previous);
            if (outer) CURRENT.remove();
        }
    }
}