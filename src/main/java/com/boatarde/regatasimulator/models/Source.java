package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Document;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Document(collection = "sources", schemaVersion = "1.1")
public class Source extends CommonEntity {
    String description;
}