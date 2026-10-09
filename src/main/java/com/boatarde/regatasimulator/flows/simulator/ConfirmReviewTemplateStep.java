package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.service.TemplateService;
import org.springframework.beans.factory.annotation.Value;

@WorkflowStepRegistration(WorkflowAction.CONFIRM_REVIEW_TEMPLATE)
public class ConfirmReviewTemplateStep implements WorkflowStep {

    private final String botAuthorId;
    private final TemplateService templateService;

    public ConfirmReviewTemplateStep(@Value("${telegram.creator.id}") String botAuthorId,
                                     TemplateService templateService) {
        this.botAuthorId = botAuthorId;
        this.templateService = templateService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        ReviewCallbackSupport.confirm(bag, "template", botAuthorId, templateService::getTemplate,
            templateService::completePreviewReview);
        return WorkflowAction.NONE;
    }
}
