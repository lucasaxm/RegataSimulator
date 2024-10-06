package com.boatarde.regatasimulator.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class Template {
    private UUID id;
    private List<TemplateArea> areas;
    private Author author;
    private LocalDateTime createdAt;
}