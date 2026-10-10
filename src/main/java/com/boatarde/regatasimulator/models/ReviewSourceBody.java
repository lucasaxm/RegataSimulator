package com.boatarde.regatasimulator.models;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Data
public class ReviewSourceBody {
    @NotNull
    private UUID sourceId;
    @NotNull
    private Boolean approved;
    @NotNull
    @Size(max = 1000)
    private String reason;

    public boolean isApproved() {
        return Boolean.TRUE.equals(approved);
    }
}
