package com.boatarde.regatasimulator.flows.common;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.TelegramTestFactory;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowDataKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SendMessageStepTest {
    @Mock
    private RegataSimulatorBot regataSimulatorBot;

    @Mock
    private SendMessage sendMessage;

    private SendMessageStep step;

    @BeforeEach
    void setUp() {
        step = new SendMessageStep();
    }

    @Test
    void testSentSuccessfully() throws Exception {
        WorkflowDataBag workflowDataBag = new WorkflowDataBag();
        workflowDataBag.put(WorkflowDataKey.SEND_MESSAGE, sendMessage);
        workflowDataBag.put(WorkflowDataKey.REGATA_SIMULATOR_BOT, regataSimulatorBot);
        when(regataSimulatorBot.execute(sendMessage)).thenReturn(TelegramTestFactory.buildTextMessage("anything"));

        WorkflowAction nextStep = step.run(workflowDataBag);

        verify(regataSimulatorBot).execute(sendMessage);

        assertEquals(WorkflowAction.NONE, nextStep);
    }
}