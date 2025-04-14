package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.models.Status;

import java.util.ArrayList;
import java.util.List;

public class JxQueryBuilder {
    private final List<String> conditions = new ArrayList<>();

    public JxQueryBuilder withStatus(Status... statuses) {
        if (statuses != null && statuses.length > 0) {
            List<String> statusConditions = new ArrayList<>();
            for (Status status : statuses) {
                if (status != null) {
                    statusConditions.add("status='" + status + "'");
                }
            }
            // Only add the condition group if there is at least one valid condition.
            if (!statusConditions.isEmpty()) {
                // Enclose OR conditions in parentheses to ensure proper evaluation.
                conditions.add("(" + String.join(" or ", statusConditions) + ")");
            }
        }
        return this;
    }

    public JxQueryBuilder withUserId(Long... userIds) {
        if (userIds != null && userIds.length > 0) {
            List<String> userConditions = new ArrayList<>();
            for (Long userId : userIds) {
                if (userId != null) {
                    userConditions.add("message/from/id=" + userId);
                }
            }
            if (!userConditions.isEmpty()) {
                conditions.add("(" + String.join(" or ", userConditions) + ")");
            }
        }
        return this;
    }

    public JxQueryBuilder withDescription(String... descriptions) {
        if (descriptions != null && descriptions.length > 0) {
            List<String> descConditions = new ArrayList<>();
            for (String description : descriptions) {
                if (description != null && !description.isBlank()) {
                    descConditions.add(
                        "contains(translate(description, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), '" +
                            description + "')");
                }
            }
            if (!descConditions.isEmpty()) {
                conditions.add("(" + String.join(" or ", descConditions) + ")");
            }
        }
        return this;
    }

    public String build() {
        if (conditions.isEmpty()) {
            return "/.";
        }
        return "/.[" + String.join(" and ", conditions) + "]";
    }
}
