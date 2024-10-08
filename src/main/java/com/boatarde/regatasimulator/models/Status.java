package com.boatarde.regatasimulator.models;

import lombok.Getter;

@Getter
public enum Status {
    REVIEW("REVIEW"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED");

    private final String value;

    Status(String value) {
        this.value = value;
    }

}
