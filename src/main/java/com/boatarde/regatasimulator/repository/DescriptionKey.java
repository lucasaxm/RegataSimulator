package com.boatarde.regatasimulator.repository;

import java.text.Normalizer;
import java.util.Locale;

public final class DescriptionKey {
    private DescriptionKey() { }
    public static String of(String description) {
        return description == null ? null : Normalizer.normalize(description.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}