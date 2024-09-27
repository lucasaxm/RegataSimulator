package com.boatarde.regatasimulator.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class GalleryResponse {
    private List<GalleryItem> items;
    private int totalItems;

    public GalleryResponse(List<GalleryItem> items, int totalItems) {
        this.items = items;
        this.totalItems = totalItems;
    }
}
