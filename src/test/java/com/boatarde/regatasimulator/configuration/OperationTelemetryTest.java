package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.service.MemeService;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationTelemetryTest {
    @Test void typedFailuresRecordDurationOutcomeWithoutSensitiveTagsAndReleaseMdc() throws Throwable {
        var registry=new SimpleMeterRegistry(); var telemetry=new OperationTelemetry(registry);
        var call=mock(ProceedingJoinPoint.class); var signature=mock(Signature.class);
        when(call.getSignature()).thenReturn(signature); when(signature.getDeclaringType()).thenReturn((Class)MemeService.class); when(signature.getName()).thenReturn("publish");
        when(call.proceed()).thenAnswer(invocation -> {
            assertNotNull(MDC.get("operationId"));
            throw new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE,"secret-token-bearing-url-not-for-logs");
        });
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(OperationTelemetry.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            assertThrows(ApplicationFailure.class,()->telemetry.invoke(call)); assertNull(MDC.get("operationId"));
            assertEquals(1,logs.list.size());
            String message=logs.list.getFirst().getFormattedMessage(); assertTrue(message.contains("operationId="));
            assertFalse(message.contains("secret-token")); assertNull(logs.list.getFirst().getThrowableProxy());
        } finally { logger.detachAppender(logs); logs.stop(); }
        assertEquals(1,registry.get("regata.operation.count").tag("operation","MemeService.publish").tag("outcome","unavailable").counter().count());
        assertEquals(1,registry.get("regata.operation.duration").timer().count());
        for(var meter:registry.getMeters()) {
            assertEquals(2,meter.getId().getTags().size());
            assertFalse(meter.getId().toString().contains("secret")); assertFalse(meter.getId().toString().contains("operationId"));
        }
    }
}