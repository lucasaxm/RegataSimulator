package com.boatarde.regatasimulator.dto;

import com.boatarde.regatasimulator.models.Status;
import lombok.Data;

@Data
public class SearchCriteria {
    private String query;
    private Status status;
    private int page;
    private int perPage;
}
