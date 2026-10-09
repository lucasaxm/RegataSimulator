package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowDataBag;
import com.boatarde.regatasimulator.flows.WorkflowStep;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.service.TemplateService;

@WorkflowStepRegistration(WorkflowAction.DELETE_REVIEW_TEMPLATE)
public class DeleteReviewTemplateStep implements WorkflowStep {

    private final TemplateService templateService;

    public DeleteReviewTemplateStep(TemplateService templateService) {
        this.templateService = templateService;
    }

    @Override
    public WorkflowAction run(WorkflowDataBag bag) {
        ReviewCallbackSupport.cancel(bag, "template", templateService::getTemplate, templateService::deleteTemplate);
        return WorkflowAction.NONE;
    }
}
