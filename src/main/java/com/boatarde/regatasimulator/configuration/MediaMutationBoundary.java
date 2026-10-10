package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.application.MediaMutationGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Outer boundary precedes callback stripes, metadata transactions and nested service calls. */
@Aspect
@Component
@Order(0)
public class MediaMutationBoundary {
    private final MediaMutationGuard guard;
    public MediaMutationBoundary(MediaMutationGuard guard) { this.guard = guard; }

    @Around("execution(public * com.boatarde.regatasimulator.service.SourceService.*(..)) || "
        + "execution(public * com.boatarde.regatasimulator.service.TemplateService.*(..)) || "
        + "execution(public * com.boatarde.regatasimulator.service.SubmissionService.*(..)) || "
        + "execution(public * com.boatarde.regatasimulator.service.SourceImporterService.*(..)) || "
        + "execution(public * com.boatarde.regatasimulator.service.MemeService.*(..)) || "
        + "execution(public * com.boatarde.regatasimulator.service.ModerationService.*(..)) || "
        + "execution(public * com.boatarde.regatasimulator.service.ReviewCallbackService.*(..))")
    public Object invoke(ProceedingJoinPoint call) throws Throwable {
        try (var lease = guard.mutation()) { return call.proceed(); }
    }
}