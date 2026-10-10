package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionNotificationTest {
    private WorkflowStep step(int index) {
        return switch (index) {
            case 0 -> new SendSourceApprovedMessageStep();
            case 1 -> new SendSourceRejectedMessageStep();
            case 2 -> new SendTemplateApprovedMessageStep();
            default -> new SendTemplateRejectedMessageStep();
        };
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void nullOriginIsAnExplicitNoNotificationOutcome(int index) {
        RegataSimulatorBot bot = mock(RegataSimulatorBot.class);
        WorkflowDataBag bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, new Update());
        assertEquals(WorkflowAction.NONE, step(index).run(bag));
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void transportFailureIsClassifiedRatherThanReportedAsSuccess(int index) throws Exception {
        RegataSimulatorBot bot = mock(RegataSimulatorBot.class);
        doThrow(new TelegramApiException("synthetic failure")).when(bot).execute(any(SendMessage.class));
        WorkflowDataBag bag = new WorkflowDataBag();
        bag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, bot);
        Update update = TelegramTestFactory.buildTextMessageUpdate("original");
        Message reason = new Message();
        reason.setText("review reason");
        update.setChannelPost(reason);
        bag.put(WorkflowDataKey.TELEGRAM_UPDATE, update);
        assertEquals(ApplicationFailure.Kind.EXECUTION,
            assertThrows(ApplicationFailure.class, () -> step(index).run(bag)).getKind());
    }
}