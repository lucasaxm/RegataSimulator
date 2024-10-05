package com.boatarde.regatasimulator.models;

import lombok.Data;

@Data
public class ReviewTemplateBody {
    private boolean approved;
    private String reason;
}
