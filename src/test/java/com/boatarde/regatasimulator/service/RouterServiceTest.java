package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;
import com.boatarde.regatasimulator.flows.WorkflowManager;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.routes.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouterServiceTest {

    @Mock
    private WorkflowManager workflowManager;

    @Mock
    private Update update;

    @Mock
    private TelegramBot bot;

    @Test
    void shouldRouteToOneAction() {
        Route route1 = mock(Route.class);
        Route route2 = mock(Route.class);
        when(route1.test(update, bot)).thenReturn(Optional.of(WorkflowAction.BUILD_PONG_MESSAGE));
        when(route2.test(update, bot)).thenReturn(Optional.empty());

        when(workflowManager.getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE)).thenReturn(Optional.empty());

        RouterService routerService = new RouterService(workflowManager, List.of(route1, route2));
        assertThrows(ApplicationFailure.class, () -> routerService.route(update, bot));
        verify(workflowManager).getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE);
    }

    @Test
    void shouldNotRoute() {
        Route route1 = mock(Route.class);
        Route route2 = mock(Route.class);
        when(route1.test(update, bot)).thenReturn(Optional.empty());
        when(route2.test(update, bot)).thenReturn(Optional.empty());

        RouterService routerService = new RouterService(workflowManager, List.of(route1, route2));
        routerService.route(update, bot);
        verifyNoInteractions(workflowManager);
    }

    @Test
    void ambiguousRoutesAreRejectedBeforeAnyStepRuns() {
        Route route1 = mock(Route.class);
        Route route2 = mock(Route.class);
        when(route1.test(update, bot)).thenReturn(Optional.of(WorkflowAction.BUILD_PONG_MESSAGE));
        when(route2.test(update, bot)).thenReturn(Optional.of(WorkflowAction.BUILD_PONG_MESSAGE));

        RouterService routerService = new RouterService(workflowManager, List.of(route1, route2));
        assertThrows(ApplicationFailure.class, () -> routerService.route(update, bot));
        verifyNoInteractions(workflowManager);
    }

    @Test
    void shouldRouteOneTimeAndExecuteNextStep() {
        Route route1 = mock(Route.class);
        Route route2 = mock(Route.class);
        when(route1.test(update, bot)).thenReturn(Optional.empty());
        when(route2.test(update, bot)).thenReturn(Optional.of(WorkflowAction.BUILD_PONG_MESSAGE));

        WorkflowStep firstStep = mock(WorkflowStep.class);
        WorkflowStep nextStep = mock(WorkflowStep.class);
        when(workflowManager.getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE)).thenReturn(Optional.of(firstStep));
        when(firstStep.run(any())).thenReturn(WorkflowAction.SEND_MESSAGE_STEP);
        when(workflowManager.getStepByEnum(WorkflowAction.SEND_MESSAGE_STEP)).thenReturn(Optional.of(nextStep));
        when(nextStep.run(any())).thenReturn(WorkflowAction.NONE);
        RouterService routerService = new RouterService(workflowManager, List.of(route1, route2));
        routerService.route(update, bot);

        verify(workflowManager).getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE);
        verify(workflowManager).getStepByEnum(WorkflowAction.SEND_MESSAGE_STEP);
        verify(firstStep).run(any());
        verify(nextStep).run(any());
    }

    @Test
    void missingAnnotationFailsWithUsefulErrorInsteadOfNullPointer() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new WorkflowManager(List.of(bag -> WorkflowAction.NONE)));
        assertTrue(failure.getMessage().contains("missing @WorkflowStepRegistration"));
    }

    @WorkflowStepRegistration(WorkflowAction.NONE)
    private static class TerminalStep implements WorkflowStep {
        public WorkflowAction run(WorkflowDataBag bag) { return WorkflowAction.NONE; }
    }

    @Test
    void terminalActionCannotBeRegistered() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowManager(List.of(new TerminalStep())));
    }

    @Test
    void cycleIsBounded() {
        WorkflowStep loop = mock(WorkflowStep.class);
        when(workflowManager.getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE)).thenReturn(Optional.of(loop));
        when(loop.run(any())).thenReturn(WorkflowAction.BUILD_PONG_MESSAGE);

        assertThrows(ApplicationFailure.class, () -> new RouterService(workflowManager, List.of())
            .startFlow(null, bot, WorkflowAction.BUILD_PONG_MESSAGE));
        verify(loop, times(64)).run(any());
    }

    @Test
    void nullTransitionFailsRatherThanCompleting() {
        WorkflowStep step = mock(WorkflowStep.class);
        when(workflowManager.getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE)).thenReturn(Optional.of(step));
        assertThrows(ApplicationFailure.class, () -> new RouterService(workflowManager, List.of())
            .startFlow(null, bot, WorkflowAction.BUILD_PONG_MESSAGE));
    }

    @Test
    void failureIsRedactedAndJobArtifactsAreCleaned(@TempDir Path temporary) throws Exception {
        RegataSimulatorBot transport = mock(RegataSimulatorBot.class);
        Update request = TelegramTestFactory.buildTextMessageUpdate("/meme");
        Path job = Files.createDirectory(temporary.resolve("job"));
        Files.writeString(job.resolve("output.png"), "synthetic output");
        WorkflowStep step = bag -> {
            bag.put(WorkflowDataKey.RENDER_JOB_DIRECTORY, job);
            throw new IllegalStateException("secret-token-path");
        };
        when(workflowManager.getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE)).thenReturn(Optional.of(step));

        assertThrows(ApplicationFailure.class, () -> new RouterService(workflowManager, List.of())
            .startFlow(request, transport, WorkflowAction.BUILD_PONG_MESSAGE));
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(transport).execute(sent.capture());
        assertFalse(sent.getValue().getText().contains("secret-token-path"));
        assertTrue(sent.getValue().getText().contains("Não foi possível"));
        assertFalse(Files.exists(job));
    }

    @Test
    void failureNotificationFailureDoesNotReplaceOriginalFailure() throws Exception {
        RegataSimulatorBot transport = mock(RegataSimulatorBot.class);
        org.mockito.Mockito.doThrow(new TelegramApiException("notification failed"))
            .when(transport).execute(any(SendMessage.class));
        ApplicationFailure original = new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "No media");
        when(workflowManager.getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE))
            .thenReturn(Optional.of(bag -> { throw original; }));
        assertSame(original, assertThrows(ApplicationFailure.class, () -> new RouterService(workflowManager, List.of())
            .startFlow(TelegramTestFactory.buildTextMessageUpdate("/meme"), transport, WorkflowAction.BUILD_PONG_MESSAGE)));
    }
}