package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.service.SourceService;
import org.springframework.beans.factory.annotation.Value;

@WorkflowStepRegistration(WorkflowAction.CONFIRM_REVIEW_SOURCE)
public class ConfirmReviewSourceStep implements WorkflowStep {

    private final String botAuthorId;
    private final SourceService sourceService;

    public ConfirmReviewSourceStep(@Value("${telegram.creator.id}") String botAuthorId,
                                   SourceService sourceService) {
        this.botAuthorId = botAuthorId;
        this.sourceService = sourceService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        ReviewCallbackSupport.confirm(bag, "source", botAuthorId, sourceService::getSource,
            sourceService::completePreviewReview);
        return WorkflowAction.NONE;
    }
}
