package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Document;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Builder
@Document(collection = "sources", schemaVersion = "1.0")
public class Source extends CommonEntity {
}