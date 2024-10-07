package com.boatarde.regatasimulator.models;

import io.jsondb.annotation.Document;
import io.jsondb.annotation.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
@Document(collection = "sources", schemaVersion= "1.0")
public class Source {
    @Id
    private UUID id;
    private Author author;
    private LocalDateTime createdAt;
    private int weight;
}