package com.boatarde.regatasimulator.models;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Coordinates {
    private Integer x;
    private Integer y;
    private boolean overlay;
}
