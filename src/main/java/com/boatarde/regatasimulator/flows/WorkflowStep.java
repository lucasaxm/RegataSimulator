package com.boatarde.regatasimulator.flows;

public interface WorkflowStep {
    WorkflowAction run(WorkflowDataBag bag);
}
