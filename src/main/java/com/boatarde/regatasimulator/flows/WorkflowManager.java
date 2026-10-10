package com.boatarde.regatasimulator.flows;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class WorkflowManager {
    private final Map<WorkflowAction, WorkflowStep> stepMap = new HashMap<>();

    public WorkflowManager(List<WorkflowStep> steps) {
        for (WorkflowStep step : steps) {
            Optional<WorkflowAction> stepEnumOptional = getStepEnum(step);
            if (stepEnumOptional.isEmpty()) {
                throw new IllegalArgumentException("Workflow step is missing @WorkflowStepRegistration: "
                    + step.getClass().getSimpleName());
            } else {
                WorkflowAction workflowAction = stepEnumOptional.get();
                if (workflowAction == WorkflowAction.NONE) {
                    throw new IllegalArgumentException("NONE is terminal and cannot be registered");
                }
                if (stepMap.containsKey(workflowAction)) {
                    throw new IllegalArgumentException(
                        String.format("Can't register %s to %s as it is already registered to step %s",
                            step.getClass().getSimpleName(),
                            workflowAction,
                            stepMap.get(workflowAction).getClass().getSimpleName()));
                }
                stepMap.put(workflowAction, step);
            }
        }
    }

    public Optional<WorkflowStep> getStepByEnum(WorkflowAction workflowAction) {
        return Optional.ofNullable(stepMap.get(workflowAction));
    }

    private Optional<WorkflowAction> getStepEnum(WorkflowStep step) {
        Class<? extends WorkflowStep> stepClass = step.getClass();
        WorkflowStepRegistration annotation = stepClass.getAnnotation(WorkflowStepRegistration.class);
        return Optional.ofNullable(annotation).map(WorkflowStepRegistration::value);
    }
}
