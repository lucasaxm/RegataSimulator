package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@Document(collection = "templates", schemaVersion = "1.0")
public class Template extends CommonEntity {
    private List<TemplateArea> areas;
}
