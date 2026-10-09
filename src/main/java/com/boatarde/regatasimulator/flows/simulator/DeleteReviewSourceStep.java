package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.service.SourceService;

@WorkflowStepRegistration(WorkflowAction.DELETE_REVIEW_SOURCE)
public class DeleteReviewSourceStep implements WorkflowStep {

    private final SourceService sourceService;

    public DeleteReviewSourceStep(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        ReviewCallbackSupport.cancel(bag, "source", sourceService::getSource, sourceService::deleteSource);
        return WorkflowAction.NONE;
    }
}
