package com.boatarde.regatasimulator.application;

import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.util.FileUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

public interface ImageRenderer {
    record Request(Path template, List<TemplateArea> areas, List<Path> sources, IntConsumer progress) {
        public Request { areas = List.copyOf(areas); sources = List.copyOf(sources); }
    }
    /** The caller owns the whole job until delivery finishes, including failed sends. */
    record RenderedImage(Path file, Path jobDirectory) implements AutoCloseable {
        @Override public void close() { FileUtils.deleteTree(jobDirectory); }
    }
    RenderedImage render(Request request);
}