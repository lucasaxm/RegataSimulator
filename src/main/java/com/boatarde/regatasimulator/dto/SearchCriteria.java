package com.boatarde.regatasimulator.dto;

import com.boatarde.regatasimulator.models.Status;
import lombok.Data;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;

@Data
public class SearchCriteria {
    @Size(max = 200)
    private String query;
    private Status status;
    @Min(1)
    private int page;
    @Min(1)
    @Max(100)
    private int perPage;
}
