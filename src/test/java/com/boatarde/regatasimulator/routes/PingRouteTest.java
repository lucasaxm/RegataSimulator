package com.boatarde.regatasimulator.routes;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramBot;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PingRouteTest {
    @Mock
    private RegataSimulatorBot regataSimulatorBot;

    private PingRoute pingRoute;

    @BeforeEach
    void setUp() {
        pingRoute = new PingRoute();
    }

    @Test
    void shouldRouteCommandToBuildPongMessageStep() {
        Update update = TelegramTestFactory.buildCommandTextMessageUpdate("/ping");
        Optional<WorkflowAction> maybeAction = pingRoute.test(update, regataSimulatorBot);
        assertThat(maybeAction)
            .isPresent()
            .get()
            .isEqualTo(WorkflowAction.BUILD_PONG_MESSAGE);
    }

    @Test
    void shouldRouteCommandWithBotUsernameToBuildPongMessageStep() {
        when(regataSimulatorBot.getBotUsername()).thenReturn("testuserbot");
        Update update = TelegramTestFactory.buildCommandTextMessageUpdate("/ping@testuserbot");
        Optional<WorkflowAction> maybeAction = pingRoute.test(update, regataSimulatorBot);
        assertThat(maybeAction)
            .isPresent()
            .get()
            .isEqualTo(WorkflowAction.BUILD_PONG_MESSAGE);
    }

    @Test
    void shouldNotRouteDifferentBot() {
        Update update = TelegramTestFactory.buildTextMessageUpdate("hello");
        Optional<WorkflowAction> maybeAction = pingRoute.test(update, mock(TelegramBot.class));
        assertThat(maybeAction).isNotPresent();
    }

    @Test
    void shouldNotRouteNoMessage() {
        Optional<WorkflowAction> maybeAction = pingRoute.test(new Update(), regataSimulatorBot);
        assertThat(maybeAction).isNotPresent();
    }
}