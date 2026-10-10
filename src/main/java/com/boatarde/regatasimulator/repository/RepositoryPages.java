package com.boatarde.regatasimulator.repository;

import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.util.JsonDBUtils;
import java.util.Comparator;
import java.util.List;

public final class RepositoryPages {
    private RepositoryPages() { }
    public static void validate(int page, int perPage) {
        if (page < 1 || perPage < 1 || perPage > 100) throw new IllegalArgumentException("Invalid pagination");
    }
    public static <T extends CommonEntity> GalleryResponse<T> of(List<T> items, int page, int perPage) {
        validate(page, perPage);
        Comparator<CommonEntity> order = JsonDBUtils.getComparator().thenComparing(item -> item.getId().toString()).reversed();
        return new GalleryResponse<>(items.stream().sorted(order).skip((long) (page-1)*perPage).limit(perPage).toList(), items.size());
    }
}