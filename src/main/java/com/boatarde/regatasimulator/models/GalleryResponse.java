package com.boatarde.regatasimulator.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class GalleryResponse<T> {
    private List<T> items;
    private int totalItems;

    public GalleryResponse(List<T> items, int totalItems) {
        this.items = items;
        this.totalItems = totalItems;
    }
}
