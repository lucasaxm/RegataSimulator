package com.boatarde.regatasimulator.models;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Builder
public class GalleryItem {
    private String name;
    private String imagePath;
    private String details;

    public GalleryItem(String name, String type, String details) {
        this.name = name;
        this.imagePath = "/api/image/" + type + "/" + name;
        this.details = details;
    }
}