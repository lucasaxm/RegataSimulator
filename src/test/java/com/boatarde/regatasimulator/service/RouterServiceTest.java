package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.flows.WorkflowAction;
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
        routerService.route(update, bot);
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
    void shouldRouteTwoTimes() {
        Route route1 = mock(Route.class);
        Route route2 = mock(Route.class);
        when(route1.test(update, bot)).thenReturn(Optional.of(WorkflowAction.BUILD_PONG_MESSAGE));
        when(route2.test(update, bot)).thenReturn(Optional.of(WorkflowAction.BUILD_PONG_MESSAGE));

        when(workflowManager.getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE)).thenReturn(Optional.empty());

        RouterService routerService = new RouterService(workflowManager, List.of(route1, route2));
        routerService.route(update, bot);
        verify(workflowManager, times(2)).getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE);
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
        when(firstStep.run(any())).thenReturn(WorkflowAction.SEND_MESSAGE);
        when(workflowManager.getStepByEnum(WorkflowAction.SEND_MESSAGE)).thenReturn(Optional.of(nextStep));
        RouterService routerService = new RouterService(workflowManager, List.of(route1, route2));
        routerService.route(update, bot);

        verify(workflowManager).getStepByEnum(WorkflowAction.BUILD_PONG_MESSAGE);
        verify(workflowManager).getStepByEnum(WorkflowAction.SEND_MESSAGE);
        verify(firstStep).run(any());
        verify(nextStep).run(any());
    }
}