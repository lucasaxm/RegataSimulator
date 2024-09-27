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

    public GalleryItem(String name, String type) {
        this.name = name;
        this.imagePath = "/api/image/" + type + "/" + name;
    }
}
