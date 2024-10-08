package com.boatarde.regatasimulator.models;

import lombok.Data;

import java.util.UUID;

@Data
public class ReviewTemplateBody {
    private UUID templateId;
    private boolean approved;
    private String reason;
}
