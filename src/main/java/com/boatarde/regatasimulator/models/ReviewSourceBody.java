package com.boatarde.regatasimulator.models;

import lombok.Data;

import java.util.UUID;

@Data
public class ReviewSourceBody {
    private UUID sourceId;
    private boolean approved;
    private String reason;
}
