package com.boatarde.regatasimulator.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class TemplateArea {
    private int index;
    private int source;
    private AreaCorner topLeft;
    private AreaCorner topRight;
    private AreaCorner bottomRight;
    private AreaCorner bottomLeft;
    private boolean background;
}
